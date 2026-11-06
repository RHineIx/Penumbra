package dev.rhineix.penumbra

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Serves only installed APK files; no filesystem paths or private app data can be requested. */
class ApkTransferProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val parts = uri.pathSegments
        require(parts.size == 3 && parts[0] == "apk") { "Invalid APK URI" }
        val pkg = PackageRules.validate(parts[1])
        val index = parts[2].toIntOrNull() ?: error("Invalid APK index")
        val app = providerContext().packageManager.getApplicationInfo(pkg, 0)
        val sources = listOf(app.publicSourceDir) + app.splitPublicSourceDirs.orEmpty().toList()
        require(index in sources.indices) { "APK split not found" }
        return File(sources[index]).also { require(it.isFile && it.extension == "apk") }
    }
    private fun providerContext() = checkNotNull(context)
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Read only" }
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when(it) { OpenableColumns.DISPLAY_NAME -> file.name; OpenableColumns.SIZE -> file.length(); else -> null } }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("Read only")
}
