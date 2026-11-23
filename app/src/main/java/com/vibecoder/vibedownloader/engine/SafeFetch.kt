package com.vibecoder.vibedownloader.engine

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Fetches a URL while checking every redirect hop: https only, and only to
 * hosts the caller allows. Used for app updates and engine updates.
 */
object SafeFetch {
    class Blocked(message: String) : IOException(message)

    /** [allow] gets (host, path, hop number) and says whether that hop is fine. */
    fun open(url: String, allow: (host: String, path: String, hop: Int) -> Boolean): Response {
        var current = url
        for (hop in 0 until 8) {
            val u = current.toHttpUrlOrNull() ?: throw Blocked("Not a valid address")
            if (!u.isHttps) throw Blocked("Refused a non-https address: ${u.host}")
            if (!allow(u.host, u.encodedPath, hop)) throw Blocked("Refused address: ${u.host}${u.encodedPath}")
            val request = Request.Builder().url(u).header("User-Agent", Http.USER_AGENT).build()
            val resp = Http.noRedirect.newCall(request).execute()
            if (resp.isRedirect) {
                val location = resp.header("Location")
                resp.close()
                current = location?.let { u.resolve(it)?.toString() } ?: throw Blocked("Redirect without a target")
                continue
            }
            if (!resp.isSuccessful) {
                val code = resp.code
                resp.close()
                throw HttpStatusException(code)
            }
            return resp
        }
        throw Blocked("Too many redirects")
    }

    /** GitHub release files: github.com first, then GitHub's download hosts. */
    fun githubAllow(repoPath: String): (String, String, Int) -> Boolean = { host, path, hop ->
        when {
            host == "github.com" -> path.startsWith(repoPath)
            hop > 0 -> host == "objects.githubusercontent.com" || host == "release-assets.githubusercontent.com" ||
                host.endsWith(".githubusercontent.com")
            else -> false
        }
    }

    fun sha256(input: InputStream, onBytes: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            total += n
            onBytes(total)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
