package com.vibecoder.vibedownloader.engine

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Puts finished files where people look for them: Download/VibeDownloader.
 * Android 10+ uses MediaStore (no permission needed); Android 8 and 9 write
 * to the public folder with the storage permission.
 */
object MediaSaver {
    const val FOLDER = "VibeDownloader"

    data class Saved(val uri: String, val path: String?, val displayName: String)

    fun save(context: Context, src: File, name: String, mime: String): Saved {
        // a folder the user picked in Settings comes first; if it is gone, fall back to Downloads
        val tree = usableFolder(context)
        if (tree != null) {
            try {
                return saveToFolder(context, tree, src, name, mime)
            } catch (e: Exception) {
                AppLog.w("save", "Chosen folder failed, saving to Downloads instead", e)
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveQ(context, src, name, mime) else saveLegacy(context, src, name, mime)
    }

    // ---- a folder the user picked (Storage Access Framework: works on Android 8+, no permission prompt) ----

    /** The picked folder, if the app may still write there. */
    fun usableFolder(context: Context): Uri? {
        val s = com.vibecoder.vibedownloader.Prefs(context).saveFolder ?: return null
        val uri = Uri.parse(s)
        val ok = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return if (ok) uri else null
    }

    /** Remembers a folder picked with ACTION_OPEN_DOCUMENT_TREE (null = back to the default). */
    fun setFolder(context: Context, tree: Uri?) {
        val prefs = com.vibecoder.vibedownloader.Prefs(context)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        prefs.saveFolder?.let { old ->
            if (tree == null || old != tree.toString()) runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        }
        if (tree != null) context.contentResolver.takePersistableUriPermission(tree, flags)
        prefs.saveFolder = tree?.toString()
    }

    /** "Phone storage / Movies" or "SD card / Videos" for the Settings row. */
    fun folderLabel(context: Context): String {
        val tree = usableFolder(context) ?: return "Download / $FOLDER"
        val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return "Chosen folder"
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "").trim('/')
        val where = if (volume.equals("primary", true)) "Phone storage" else if (volume.equals("home", true)) "Documents" else "SD card"
        return if (path.isEmpty()) where else where + " / " + path.replace("/", " / ")
    }

    private fun saveToFolder(context: Context, tree: Uri, src: File, name: String, mime: String): Saved {
        val resolver = context.contentResolver
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
        // the folder's app adds " (1)" itself when the name is taken
        val doc = android.provider.DocumentsContract.createDocument(resolver, parent, mime.ifBlank { "application/octet-stream" }, name)
            ?: throw IOException("Could not create the file in the chosen folder")
        try {
            val out = resolver.openOutputStream(doc) ?: throw IOException("Could not open the file in the chosen folder")
            out.use { o -> src.inputStream().use { it.copyTo(o, 1 shl 20) } }
        } catch (e: Exception) {
            runCatching { android.provider.DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
        src.delete()
        val shownName = runCatching {
            resolver.query(doc, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: name
        return Saved(doc.toString(), null, shownName)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveQ(context: Context, src: File, name: String, mime: String): Saved {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the file in Downloads")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open the file in Downloads")
            out.use { o -> src.inputStream().use { it.copyTo(o, 1 shl 20) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        src.delete()
        val shownName = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: name
        return Saved(uri.toString(), null, shownName)
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, src: File, name: String, mime: String): Saved {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create the Downloads folder")
        val dest = unique(dir, name)
        if (!src.renameTo(dest)) {
            src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 20) } }
            src.delete()
        }
        MediaScannerConnection.scanFile(context, arrayOf(dest.path), arrayOf(mime), null)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", dest)
        return Saved(uri.toString(), dest.path, dest.name)
    }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    /** Deletes a saved file. False when Android did not allow it. */
    fun delete(context: Context, uri: String?, path: String?): Boolean {
        var ok = false
        if (path != null) {
            val f = File(path)
            ok = !f.exists() || f.delete()
            if (ok) MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        } else if (uri != null) {
            val u = Uri.parse(uri)
            ok = if (android.provider.DocumentsContract.isDocumentUri(context, u)) {
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, u) }.getOrDefault(false)
            } else {
                runCatching { context.contentResolver.delete(u, null, null) >= 0 }.getOrDefault(false)
            }
        }
        return ok
    }

    fun exists(context: Context, uri: String?, path: String?): Boolean {
        if (path != null) return File(path).exists()
        if (uri == null) return false
        return runCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    fun openIntent(uri: String, mime: String): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shareIntent(uri: String, mime: String, title: String): Intent {
        val u = Uri.parse(uri)
        val send = Intent(Intent.ACTION_SEND).setType(mime.ifBlank { "*/*" })
            .putExtra(Intent.EXTRA_STREAM, u)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(title, u)
        return Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
