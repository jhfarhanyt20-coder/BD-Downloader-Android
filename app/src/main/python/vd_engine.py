"""VibeDownloader media helper, called from Kotlin through Chaquopy.

Every public function returns a JSON string, so nothing Python-specific
crosses into the app. There is no FFmpeg on the phone: yt-dlp only ever
downloads single streams here, and the app merges a video stream with an
audio stream itself (Android MediaMuxer).
"""

import importlib
import json
import os
import re
import sys
import threading
import traceback

# YouTube player clients, tried in this order when a download is refused.
# None means "yt-dlp's own default". Unknown names are skipped by yt-dlp
# with a warning, so an older or newer yt-dlp never fails because of them.
CLIENT_SETS = [
    None,
    ["android_vr"],
    ["visionos"],
    ["ios"],
    ["android", "web"],
    ["tv", "web_safari"],
    ["tv_samsung", "tv_downgraded"],
    ["mweb"],
    ["web_embedded"],
]

_BUCKETS = (2160, 1440, 1080, 720, 480, 360, 240, 144)
_OFFER = (1080, 720, 480, 360)
_MUX_VIDEO = ("avc1", "avc3", "h264", "hev1", "hvc1", "h265")
_PROTO_RANK = {"https": 3, "http": 3, "m3u8_native": 2, "http_dash_segments": 1}

_lock = threading.RLock()
_added_paths = []
_state = {"source": "bundled", "version": "", "error": ""}


class _Stopped(Exception):
    """Raised from the progress hook when the user paused or cancelled."""


class _NoFormat(Exception):
    pass


class _Log:
    """yt-dlp logger: keeps the last lines for the report, passes warnings on."""

    def __init__(self, sink=None):
        self.sink = sink
        self.lines = []

    def _keep(self, level, msg):
        msg = _clean(msg)
        self.lines.append(level + ": " + msg[:600])
        del self.lines[:-30]
        if self.sink is not None and level in ("warning", "error"):
            try:
                self.sink.log(level + ": " + msg[:1500])
            except Exception:
                pass

    def debug(self, msg):
        if str(msg).startswith("[debug] "):
            return
        self._keep("debug", msg)

    def info(self, msg):
        self._keep("info", msg)

    def warning(self, msg):
        self._keep("warning", msg)

    def error(self, msg):
        self._keep("error", msg)


def _clean(text):
    text = re.sub(r"\x1b\[[0-9;]*m", "", str(text))
    return text.replace("ERROR: ", "").strip()


# --------------------------------------------------------------- loading
def _purge():
    for name in list(sys.modules):
        if name == "yt_dlp" or name.startswith("yt_dlp."):
            sys.modules.pop(name, None)
    importlib.invalidate_caches()


def load(zip_path=None):
    """Use a downloaded yt-dlp zipapp first; fall back to the bundled copy."""
    with _lock:
        for path in _added_paths:
            while path in sys.path:
                sys.path.remove(path)
        del _added_paths[:]
        _purge()
        error = ""
        if zip_path and os.path.isfile(zip_path):
            sys.path.insert(0, zip_path)
            _added_paths.append(zip_path)
            try:
                mod = importlib.import_module("yt_dlp")
                where = os.path.abspath(getattr(mod, "__file__", "") or "")
                if not where.startswith(os.path.abspath(zip_path)):
                    raise ImportError("the downloaded copy was not used")
                getattr(mod, "YoutubeDL")
                from yt_dlp.version import __version__ as version
                _state.update(source="update", version=version, error="")
                return json.dumps(dict(_state, ok=True))
            except Exception as exc:  # noqa: BLE001 - any failure means "use bundled"
                error = "%s: %s" % (type(exc).__name__, exc)
                while zip_path in sys.path:
                    sys.path.remove(zip_path)
                del _added_paths[:]
                _purge()
        importlib.import_module("yt_dlp")
        from yt_dlp.version import __version__ as version
        _state.update(source="bundled", version=version, error=error)
        return json.dumps(dict(_state, ok=not error))


def info():
    return json.dumps(dict(_state))


def client_set_count():
    return len(CLIENT_SETS)


# --------------------------------------------------------------- options
def _opts(cache_dir, client_index, log):
    opts = {
        "quiet": True,
        "no_warnings": False,
        "noprogress": True,
        "logger": log,
        "retries": 10,
        "fragment_retries": 10,
        "extractor_retries": 3,
        "file_access_retries": 3,
        "socket_timeout": 30,
        "concurrent_fragment_downloads": 4,
        # 10 MB requests: this is what keeps YouTube from throttling or refusing
        "http_chunk_size": 10 * 1024 * 1024,
        "continuedl": True,
        "noplaylist": True,
        "playlistend": 1,
        "check_formats": False,
        # selection here never fails; the app picks the real formats itself
        "format": "best/bestvideo/bestaudio",
        # never hand anything to FFmpeg (there is none on the phone)
        "external_downloader": {"default": "native"},
        "cachedir": os.path.join(cache_dir, "yt-dlp") if cache_dir else False,
    }
    clients = CLIENT_SETS[client_index] if 0 <= client_index < len(CLIENT_SETS) else None
    if clients:
        opts["extractor_args"] = {"youtube": {"player_client": list(clients)}}
    return opts


def _extract(url, cache_dir, client_index, log):
    import yt_dlp

    ydl = yt_dlp.YoutubeDL(_opts(cache_dir, client_index, log))
    result = ydl.extract_info(url, download=False)
    for _ in range(3):
        if not result:
            break
        entries = result.get("entries")
        if result.get("_type") in ("playlist", "multi_video") or entries is not None:
            items = [e for e in list(entries or []) if e]
            if not items:
                raise RuntimeError("Unsupported URL: no video found on this page")
            result = items[0]
            if result.get("_type") in ("url", "url_transparent") and result.get("url"):
                result = ydl.extract_info(result["url"], download=False)
            continue
        break
    if not result:
        raise RuntimeError("Unsupported URL: nothing found")
    return ydl, result


# --------------------------------------------------------------- formats
def _vc(f):
    return f.get("vcodec")


def _ac(f):
    return f.get("acodec")


def _usable(f):
    if not f.get("url") or f.get("has_drm"):
        return False
    if (f.get("ext") or "") == "mhtml":
        return False
    proto = str(f.get("protocol") or "")
    if "mhtml" in proto or proto.startswith(("f4m", "rtmp", "rtsp", "ism", "websocket")):
        return False
    if "storyboard" in str(f.get("format_note") or "").lower():
        return False
    return True


def _video_only(f):
    return _vc(f) not in (None, "none") and _ac(f) == "none"


def _audio_only(f):
    if _vc(f) == "none" and _ac(f) not in (None, "none"):
        return True
    return f.get("video_ext") == "none" and f.get("audio_ext") not in (None, "none")


def _progressive(f):
    if _video_only(f) or _audio_only(f):
        return False
    if _vc(f) == "none" or _ac(f) == "none":
        return False
    return bool(f.get("height") or f.get("width") or _vc(f) not in (None, "none"))


def _short_side(f):
    w, h = f.get("width") or 0, f.get("height") or 0
    if w and h:
        return min(w, h)
    return h or 0


def _bucket(f):
    p = _short_side(f)
    for b in _BUCKETS:
        if p >= b * 0.85:
            return b
    return 144 if p else 0


def _muxable_video(f):
    return (_video_only(f) and f.get("ext") in ("mp4", "m4v")
            and str(_vc(f)).lower().startswith(_MUX_VIDEO))


def _muxable_audio(f):
    if not _audio_only(f):
        return False
    ac = str(_ac(f) or "").lower()
    if ac.startswith(("mp4a", "aac")):
        return True
    return f.get("ext") == "m4a" and not ac.startswith(("opus", "vorbis"))


def _vrank(f):
    vc = str(_vc(f) or "").lower()
    return (
        1 if vc.startswith(("avc1", "avc3", "h264")) else 0,
        _PROTO_RANK.get(str(f.get("protocol") or ""), 0),
        f.get("tbr") or f.get("vbr") or 0,
        _size(f),
    )


def _arank(f):
    fid = str(f.get("format_id") or "")
    return (
        f.get("language_preference") or 0,
        0 if "drc" in fid else 1,
        _PROTO_RANK.get(str(f.get("protocol") or ""), 0),
        f.get("abr") or f.get("tbr") or 0,
    )


def _size(f):
    try:
        return int(f.get("filesize") or f.get("filesize_approx") or 0)
    except (TypeError, ValueError):
        return 0


def _formats(info):
    fmts = [f for f in (info.get("formats") or []) if _usable(f)]
    if not fmts and info.get("url") and _usable(info):
        fmts = [info]
    return fmts


def _choose(info, quality, prefer_single=False):
    """Return {"mode": single|merge|audio, "parts": [(role, format), ...], "height": n}."""
    fmts = _formats(info)
    audios = sorted([f for f in fmts if _muxable_audio(f)], key=_arank, reverse=True)
    if quality == "audio":
        pick = audios or sorted([f for f in fmts if _audio_only(f)], key=_arank, reverse=True)
        if not pick:
            raise _NoFormat("Requested format is not available: no audio stream")
        return {"mode": "audio", "parts": [("audio", pick[0])], "height": 0}

    progs = [f for f in fmts if _progressive(f)]
    vids = [f for f in fmts if _muxable_video(f)] if audios else []
    if quality == "best":
        heights = [_bucket(f) for f in progs] + ([] if prefer_single else [_bucket(f) for f in vids])
        target = max(heights or [0])
    else:
        try:
            target = int(quality)
        except (TypeError, ValueError):
            target = max([_bucket(f) for f in progs + vids] or [0])

    order = [b for b in _BUCKETS if b <= target] + [0]
    for b in order:
        mp4 = [f for f in progs if _bucket(f) == b and f.get("ext") in ("mp4", "m4v", "mov")]
        if mp4:
            return {"mode": "single", "parts": [("video", max(mp4, key=_vrank))], "height": b}
        if not prefer_single:
            v = [f for f in vids if _bucket(f) == b]
            if v:
                return {"mode": "merge", "parts": [("video", max(v, key=_vrank)), ("audio", audios[0])], "height": b}
        other = [f for f in progs if _bucket(f) == b]
        if other:
            return {"mode": "single", "parts": [("video", max(other, key=_vrank))], "height": b}

    if progs:
        best = max(progs, key=lambda f: (_bucket(f), _vrank(f)))
        return {"mode": "single", "parts": [("video", best)], "height": _bucket(best)}
    plain = [f for f in fmts if not _video_only(f) and not _audio_only(f)]
    if plain:
        return {"mode": "single", "parts": [("video", plain[-1])], "height": _bucket(plain[-1])}
    if vids:
        best = max(vids, key=lambda f: (_bucket(f), _vrank(f)))
        return {"mode": "merge", "parts": [("video", best), ("audio", audios[0])], "height": _bucket(best)}
    raise _NoFormat("Requested format is not available")


def _plan_size(plan):
    return sum(_size(f) for _, f in plan["parts"])


def _qualities(info):
    out = []
    try:
        best = _choose(info, "best")
        h = best["height"]
        out.append({"key": "best", "label": "Best", "detail": ("%dp" % h) if h else "Original",
                    "size": _plan_size(best)})
    except _NoFormat:
        pass
    fmts = _formats(info)
    has_audio = any(_muxable_audio(f) for f in fmts)
    for b in _OFFER:
        exists = any(_bucket(f) == b for f in fmts if _progressive(f)) or (
            has_audio and any(_bucket(f) == b for f in fmts if _muxable_video(f)))
        if not exists:
            continue
        try:
            plan = _choose(info, str(b))
        except _NoFormat:
            continue
        if plan["height"] == b:
            out.append({"key": str(b), "label": "%dp" % b, "detail": "MP4", "size": _plan_size(plan)})
    try:
        audio = _choose(info, "audio")
        f = audio["parts"][0][1]
        ext = str(f.get("ext") or "m4a").upper()
        out.append({"key": "audio", "label": "Audio only", "detail": ext, "size": _plan_size(audio)})
    except _NoFormat:
        pass
    return out


def _safe(text):
    return re.sub(r"[^A-Za-z0-9_-]+", "_", str(text or "x"))[:40] or "x"


def _error(exc, log):
    return json.dumps({
        "ok": False,
        "error": _clean("%s" % exc)[:1500],
        "log": log.lines[-10:],
        "trace": traceback.format_exc()[-2500:],
    })


# --------------------------------------------------------------- public
def probe(url, client_index, cache_dir):
    log = _Log()
    try:
        _, data = _extract(url, cache_dir, int(client_index), log)
        if data.get("is_live"):
            raise RuntimeError("This live event is live now")
        qualities = _qualities(data)
        if not qualities:
            raise _NoFormat("Requested format is not available: no video formats found")
        thumb = data.get("thumbnail") or ""
        if not thumb:
            thumbs = [t for t in (data.get("thumbnails") or []) if t.get("url")]
            if thumbs:
                thumb = thumbs[-1]["url"]
        return json.dumps({
            "ok": True,
            "title": data.get("title") or data.get("id") or "Video",
            "thumbnail": thumb,
            "duration": int(data.get("duration") or 0),
            "uploader": data.get("uploader") or data.get("channel") or "",
            "site": data.get("extractor_key") or data.get("extractor") or "",
            "webpage": data.get("webpage_url") or url,
            "qualities": qualities,
        })
    except Exception as exc:  # noqa: BLE001
        return _error(exc, log)


def download(url, quality, prefer_single, work_dir, cache_dir, client_index, sink):
    log = _Log(sink)
    try:
        ydl, data = _extract(url, cache_dir, int(client_index), log)
        if sink.isCancelled():
            raise _Stopped()
        if data.get("is_live"):
            raise RuntimeError("This live event is live now")
        plan = _choose(data, str(quality), bool(prefer_single))
        parts = plan["parts"]
        sizes = [_size(f) for _, f in parts]
        current = {"i": 0}

        def hook(d):
            if sink.isCancelled():
                raise _Stopped()
            if d.get("status") != "downloading":
                return
            i = current["i"]
            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            if total:
                sizes[i] = int(total)
            done = sum(sizes[:i]) + int(d.get("downloaded_bytes") or 0)
            sink.onProgress(int(done), int(sum(sizes)), float(d.get("speed") or 0.0))

        ydl.add_progress_hook(hook)
        os.makedirs(work_dir, exist_ok=True)
        files = []
        for i, (role, f) in enumerate(parts):
            current["i"] = i
            ext = f.get("ext") or "mp4"
            path = os.path.join(work_dir, "%s_%s.%s" % (role, _safe(f.get("format_id")), ext))
            finished = (os.path.isfile(path) and os.path.getsize(path) > 0
                        and not os.path.exists(path + ".part"))
            if finished:
                sizes[i] = os.path.getsize(path)
            else:
                job = dict(data)
                for key in ("requested_formats", "requested_downloads", "requested_subtitles"):
                    job.pop(key, None)
                job.update(f)
                ok = ydl.dl(path, job)
                if sink.isCancelled():
                    raise _Stopped()
                if not ok or not os.path.isfile(path):
                    raise RuntimeError("The download did not finish")
            files.append({"role": role, "path": path, "ext": ext,
                          "protocol": str(f.get("protocol") or "")})
        return json.dumps({
            "ok": True,
            "mode": plan["mode"],
            "files": files,
            "title": data.get("title") or data.get("id") or "",
            "site": data.get("extractor_key") or "",
        })
    except _Stopped:
        return json.dumps({"ok": False, "stopped": True})
    except Exception as exc:  # noqa: BLE001
        try:
            if sink.isCancelled():
                return json.dumps({"ok": False, "stopped": True})
        except Exception:
            pass
        return _error(exc, log)
