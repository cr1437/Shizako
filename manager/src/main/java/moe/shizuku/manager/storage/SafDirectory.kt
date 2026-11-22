package moe.shizuku.manager.storage

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.InputStream
import java.io.OutputStream

/**
 * SAF 目录的读写权限被回收 / 失效（`SecurityException`）。
 *
 * 单独一个异常类型是给 UI 用的：以前 `list()` 把异常吞掉返回空列表，界面就显示
 * 「这个目录里没有备份」，用户根本不知道是权限没了；现在调用方可以据此提示
 * 「已没有该目录的访问权限，请重新选择」。
 */
class SafAccessException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * SAF 目录（规格 F3：用户通过 SAF 选择目录，备份文件写进去）。
 *
 * 直接用 [DocumentsContract] 而不是 androidx.documentfile：
 * - 少一个依赖（本项目的构建环境拉新依赖不稳，历史上有卡在镜像上的情况）；
 * - 需要的三件事（建文件 / 读文件 / 列目录）DocumentsContract 都有；
 * - minSdk 24，这些 API 从 19/21 就有，没有兼容问题。
 *
 * 目录的读写权限是 `OpenDocumentTree` 给的；Activity 里拿到后必须调
 * `takePersistableUriPermission`，否则进程重启后（或后台队列稍后执行时）会失效。
 * 失效时所有操作抛 [SafAccessException]，不再静默返回空。
 */
class SafDirectory(
    private val resolver: ContentResolver,
    val treeUri: Uri,
) {

    data class Entry(
        val uri: Uri,
        val name: String,
        val size: Long,
        val mimeType: String?,
        val lastModified: Long,
    )

    private val treeDocumentId: String by lazy { DocumentsContract.getTreeDocumentId(treeUri) }

    private val rootDocumentUri: Uri by lazy {
        DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
    }

    /** 用户选的目录名（界面上显示「已选：Download」用）。 */
    fun displayName(): String {
        // 树根本身不在 child 列表里，得单独查一次它的 display name
        return runCatching { queryDisplayName(rootDocumentUri) }
            .getOrNull()
            ?: (treeUri.lastPathSegment ?: "SAF")
    }

    fun list(): List<Entry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val entries = ArrayList<Entry>()
        try {
            val cursor = resolver.query(childrenUri, projection, null, null, null)
                ?: throw IllegalStateException("query returned null for $childrenUri")
            cursor.use {
                while (it.moveToNext()) {
                    val docId = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    entries.add(
                        Entry(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                            name = name,
                            size = if (it.isNull(2)) 0L else it.getLong(2),
                            mimeType = it.getString(3),
                            lastModified = if (it.isNull(4)) 0L else it.getLong(4),
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            throw SafAccessException("no permission to list $treeUri", e)
        }
        return entries
    }

    fun find(name: String): Entry? = list().firstOrNull { it.name == name }

    /**
     * 建文件。先按同名删一次：`createDocument` 在已有同名文件时有的 provider 直接失败，
     * 有的会自动改名成 `xxx (1).tar.gz`，两种都会让「恢复到哪个文件」变得不可预测。
     */
    fun createFile(name: String, mimeType: String): Uri {
        delete(name)
        val uri = guarded("createDocument") {
            DocumentsContract.createDocument(resolver, rootDocumentUri, mimeType, name)
        }
        return uri ?: throw IllegalStateException("cannot create $name in $treeUri")
    }

    fun delete(name: String): Boolean {
        val entry = find(name) ?: return false
        return try {
            DocumentsContract.deleteDocument(resolver, entry.uri)
        } catch (e: SecurityException) {
            throw SafAccessException("no permission to delete in $treeUri", e)
        } catch (e: Throwable) {
            false
        }
    }

    fun openOutput(uri: Uri): OutputStream =
        guarded("openOutputStream") {
            resolver.openOutputStream(uri, "wt")
        } ?: throw IllegalStateException("no output stream for $uri")

    fun openInput(uri: Uri): InputStream =
        guarded("openInputStream") {
            resolver.openInputStream(uri)
        } ?: throw IllegalStateException("no input stream for $uri")

    /** 直接把 SAF 文件交给服务端解压，不用在应用进程里再拷一份。 */
    fun openInputDescriptor(uri: Uri): ParcelFileDescriptor =
        guarded("openFileDescriptor") {
            resolver.openFileDescriptor(uri, "r")
        } ?: throw IllegalStateException("no fd for $uri")

    fun sizeOf(uri: Uri): Long = runCatching { querySize(uri) }.getOrDefault(0L)

    private inline fun <T> guarded(operation: String, block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw SafAccessException("$operation denied for $treeUri", e)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long = runCatching {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)
}
