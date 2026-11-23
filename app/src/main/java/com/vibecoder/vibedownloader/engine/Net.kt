package com.vibecoder.vibedownloader.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/** Is there internet, and is it Wi-Fi-like (not metered)? */
object Net {
    enum class State { None, Metered, Unmetered }

    fun state(context: Context): State {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return State.None
        return runCatching {
            val network = cm.activeNetwork ?: return State.None
            val caps = cm.getNetworkCapabilities(network) ?: return State.None
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return State.None
            val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
            if (unmetered) State.Unmetered else State.Metered
        }.getOrDefault(State.None)
    }

    fun online(context: Context) = state(context) != State.None
}

/** The shared HTTP clients. */
object Http {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /** General use: pages, update checks, thumbnails. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Downloads: HTTP/1.1 so each range really gets its own connection
     * (HTTP/2 would squeeze all of them into one).
     */
    val download: OkHttpClient by lazy {
        client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
    }

    /** No automatic redirects: every hop is checked by [SafeFetch]. */
    val noRedirect: OkHttpClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }
}
