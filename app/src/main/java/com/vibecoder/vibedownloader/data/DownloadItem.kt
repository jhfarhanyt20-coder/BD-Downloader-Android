package com.vibecoder.vibedownloader.data

import org.json.JSONObject

enum class Kind { File, Media }

enum class Status {
    Queued, Waiting, Downloading, Finishing, Paused, Failed, Done;

    val isRunning get() = this == Downloading || this == Finishing
    /** Counts for the "Active" filter (everything not finished). */
    val isActive get() = this != Done
}

/** One download, as the list shows it and as history keeps it. */
data class DownloadItem(
    val id: String,
    val url: String,
    val kind: Kind,
    val title: String,
    val fileName: String = "",
    val site: String = "",
    val thumbnail: String? = null,
    val quality: String = "best",
    val status: Status = Status.Queued,
    val downloaded: Long = 0,
    val total: Long = 0,
    val speed: Long = 0,
    /** Friendly status text: "Waiting for Wi-Fi", or the error message. */
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
    val savedUri: String? = null,
    val savedPath: String? = null,
    val mime: String = "",
    val duration: Long = 0,
    /** Was running when the app was closed; continues on the next start. */
    val interrupted: Boolean = false,
) {
    val progress: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    val etaSeconds: Long get() = if (speed > 0 && total > downloaded) (total - downloaded) / speed else 0

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("url", url).put("kind", kind.name).put("title", title)
        .put("fileName", fileName).put("site", site).put("thumbnail", thumbnail ?: "")
        .put("quality", quality).put("status", status.name).put("downloaded", downloaded)
        .put("total", total).put("note", note ?: "").put("createdAt", createdAt)
        .put("finishedAt", finishedAt).put("savedUri", savedUri ?: "").put("savedPath", savedPath ?: "")
        .put("mime", mime).put("duration", duration).put("interrupted", interrupted)

    companion object {
        fun fromJson(o: JSONObject): DownloadItem? = runCatching {
            DownloadItem(
                id = o.getString("id"),
                url = o.getString("url"),
                kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.File),
                title = o.optString("title"),
                fileName = o.optString("fileName"),
                site = o.optString("site"),
                thumbnail = o.optString("thumbnail").ifBlank { null },
                quality = o.optString("quality", "best"),
                status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.Paused),
                downloaded = o.optLong("downloaded"),
                total = o.optLong("total"),
                note = o.optString("note").ifBlank { null },
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                finishedAt = o.optLong("finishedAt"),
                savedUri = o.optString("savedUri").ifBlank { null },
                savedPath = o.optString("savedPath").ifBlank { null },
                mime = o.optString("mime"),
                duration = o.optLong("duration"),
                interrupted = o.optBoolean("interrupted"),
            )
        }.getOrNull()
    }
}
