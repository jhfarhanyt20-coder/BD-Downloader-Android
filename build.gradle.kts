plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    // Python inside the app (MIT licence), used to run yt-dlp for video and audio pages
    id("com.chaquo.python") version "16.1.0" apply false
}
