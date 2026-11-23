package com.vibecoder.vibedownloader.engine

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/** An HTTP answer that is not a success. */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** The server's answer did not match the byte range we asked for. */
class RangeMismatchException(message: String) : IOException(message)

/**
 * Every failure becomes one of these. Only [message] is ever shown to the
 * user; the technical text goes to the log.
 */
enum class Problem(val message: String, val retryable: Boolean) {
    Offline("No internet connection - it will continue when you're back online", true),
    Network("The connection keeps dropping. Please try again in a moment.", true),
    Private("This video is private or was removed", false),
    Unavailable("This video is private or was removed", true),
    SignIn("This site needs you to be signed in", true),
    AgeLimit("This video is age-restricted and needs a signed-in account", true),
    Country("This video isn't available in your country", false),
    Live("Live streams can only be downloaded after they end", false),
    Protected("This video is protected and can't be downloaded", false),
    NotFound("The file wasn't found - the link may be old or wrong", false),
    Refused("The site refused the download. Please try again later.", true),
    Busy("The site is busy right now. Please try again in a few minutes.", true),
    NoMedia("We couldn't find a video or file on this link", false),
    NoFormats("This video can't be downloaded right now. Please try again later.", true),
    Storage("Not enough free space on your phone", false),
    Unknown("Something went wrong. Please try again.", true);

    companion object {
        /** Engine and HTTP errors. */
        fun of(t: Throwable): Problem {
            var e: Throwable? = t
            while (e != null) {
                when (e) {
                    is HttpStatusException -> return when (e.code) {
                        401, 403 -> Refused
                        404, 410 -> NotFound
                        451 -> Country
                        408, 429 -> Busy
                        in 500..599 -> Busy
                        else -> Unknown
                    }
                    is UnknownHostException, is ConnectException, is InterruptedIOException,
                    is SSLException, is SocketException -> return Network
                }
                val msg = e.message?.lowercase(Locale.US) ?: ""
                if ("enospc" in msg || "no space left" in msg) return Storage
                e = e.cause
            }
            return if (t is IOException) Network else Unknown
        }

        /** yt-dlp error text. */
        fun ofMedia(text: String?): Problem {
            val m = (text ?: "").lowercase(Locale.US)
            return when {
                m.isBlank() -> Unknown
                "no space left" in m || "enospc" in m -> Storage
                "private video" in m || "video is private" in m || "has been removed" in m ||
                    "no longer available" in m || "account associated with this video has been terminated" in m ||
                    "video has been deleted" in m -> Private
                "confirm your age" in m || "age-restricted" in m || "age restricted" in m ||
                    "inappropriate for some users" in m -> AgeLimit
                "not available in your country" in m || "geo restrict" in m || "geo-restrict" in m ||
                    "in your country" in m -> Country
                "drm" in m -> Protected
                "video unavailable" in m || "this video is unavailable" in m || "content isn't available" in m -> Unavailable
                "live event" in m || "is live now" in m || "premieres in" in m || "will begin in" in m -> Live
                "sign in" in m || "log in" in m || "login required" in m || "logged-in" in m ||
                    "cookies" in m || "authentication" in m || "registered users" in m -> SignIn
                "http error 429" in m || "too many requests" in m -> Busy
                "http error 403" in m || "forbidden" in m -> Refused
                "http error 404" in m || "http error 410" in m -> NotFound
                "http error 5" in m -> Busy
                "unsupported url" in m || "no video found" in m || "nothing found" in m -> NoMedia
                "requested format" in m || "no video formats" in m || "unable to extract" in m ||
                    "nsig" in m || "signature" in m || "did not finish" in m -> NoFormats
                "timed out" in m || "getaddrinfo" in m || "name resolution" in m || "unable to resolve" in m ||
                    "network is unreachable" in m || "connection reset" in m || "connection refused" in m ||
                    "failed to establish" in m || "remote end closed" in m || "connection aborted" in m ||
                    "ssl" in m || "eof occurred" in m || "urlopen error" in m || "incompleteread" in m ||
                    "connection broken" in m || "unable to download" in m -> Network
                else -> Unknown
            }
        }
    }
}
