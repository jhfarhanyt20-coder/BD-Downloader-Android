package com.vibecoder.vibedownloader.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.vibecoder.vibedownloader.engine.FileNames
import com.vibecoder.vibedownloader.ui.theme.Vd

/** The smart file groups on the Downloads page and in the storage card. */
enum class Category(val label: String) {
    Video("Video"), Audio("Audio"), Documents("Documents"), Apk("APK"), Other("Other");

    val icon: ImageVector
        get() = when (this) {
            Video -> Icons.Rounded.OndemandVideo
            Audio -> Icons.Rounded.AudioFile
            Documents -> Icons.Rounded.Description
            Apk -> Icons.Rounded.Android
            Other -> Icons.Rounded.InsertDriveFile
        }

    /** Reads the current theme palette, so it follows Dark / Light. */
    val color: Color
        get() = when (this) {
            Video -> Vd.CatVideo
            Audio -> Vd.CatAudio
            Documents -> Vd.CatDocs
            Apk -> Vd.CatApk
            Other -> Vd.CatOther
        }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        private val DOC_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp",
            "csv", "epub", "md", "json", "xml", "html", "htm")
        private val VIDEO_EXT = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "ts", "flv", "wmv")
        private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "wma", "amr")

        fun of(item: DownloadItem): Category {
            val name = item.fileName.ifBlank { item.title }
            val ext = FileNames.extension(name)
            val mime = item.mime.ifBlank { if (ext.isNotEmpty()) FileNames.mimeFor(name) else "" }.lowercase()
            return when {
                ext == "apk" || ext == "apks" || mime == APK_MIME -> Apk
                item.kind == Kind.Media && item.quality == "audio" -> Audio
                mime.startsWith("audio/") || ext in AUDIO_EXT -> Audio
                mime.startsWith("video/") || ext in VIDEO_EXT -> Video
                item.kind == Kind.Media && ext.isEmpty() -> Video
                ext in DOC_EXT || mime.startsWith("text/") || mime == "application/pdf" ||
                    mime.contains("msword") || mime.contains("officedocument") || mime.contains("opendocument") -> Documents
                else -> Other
            }
        }
    }
}
