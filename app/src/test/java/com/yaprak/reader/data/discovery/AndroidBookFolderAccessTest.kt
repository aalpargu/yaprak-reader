package com.yaprak.reader.data.discovery

import android.app.Application
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidBookFolderAccessTest {
    private val authority = "folder.provider"
    private val tree = DocumentsContract.buildTreeDocumentUri(authority, "primary:Books").toString()
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
    private val resolver get() = ApplicationProvider.getApplicationContext<Application>().contentResolver

    @Test fun `real resolver queries exact selected root and closes named folder cursor`() = runBlocking {
        val cursor = folder("Kitaplar 🌿 İ")
        val provider = provider { uri, projection, signal ->
            assertArrayEquals(columns, projection)
            assertEquals(listOf("tree", "primary:Books", "document", "primary:Books"), uri.pathSegments)
            assertFalse(signal!!.isCanceled)
            cursor
        }
        val selected = AndroidBookFolderAccess(resolver).inspect(tree.replace("%3A", "%3a"))
        assertEquals(tree, selected.treeUri)
        assertEquals("Kitaplar 🌿 İ", selected.displayName)
        assertTrue(cursor.isClosed); assertEquals(1, provider.queries)
    }

    @Test fun `missing nonfolder malformed names and invalid tree are distinguished without persisting rights`() = runBlocking {
        val access = AndroidBookFolderAccess(resolver)
        for (name in listOf(null, " ", "bad\nname", "x".repeat(513), "\uD800")) {
            val cursor = folder(name); provider { _, _, _ -> cursor }
            assertNull(access.inspect(tree).displayName); assertTrue(cursor.isClosed)
        }
        val cursor = folder("book", "application/pdf")
        val provider = provider { _, _, _ -> cursor }
        assertTrue(runCatching { access.inspect(tree) }.exceptionOrNull() is InvalidDiscoveryTreeException)
        assertTrue(cursor.isClosed)
        val prior = provider.queries
        assertTrue(runCatching { access.inspect("$tree/document/other") }.exceptionOrNull() is InvalidDiscoveryTreeException)
        assertEquals(prior, provider.queries)
        provider { _, _, _ -> null }
        assertTrue(runCatching { access.inspect(tree) }.exceptionOrNull() is IOException)
        assertTrue(resolver.persistedUriPermissions.isEmpty())
    }

    @Test fun `actual persisted read check canonicalizes equivalent grant and release retains write and foreign rights`() {
        val access = AndroidBookFolderAccess(resolver)
        val foreign = Uri.parse("content://other.provider/tree/Elsewhere")
        resolver.takePersistableUriPermission(foreign, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val alias = Uri.parse(tree.replace("%3A", "%3a"))
        resolver.takePersistableUriPermission(alias, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertFalse(access.hasRead(tree))
        resolver.takePersistableUriPermission(alias, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(access.hasRead(tree))
        access.releaseRead(tree)
        assertFalse(access.hasRead(tree))
        val retained = resolver.persistedUriPermissions.single { it.uri == alias }
        assertTrue(retained.isWritePermission); assertFalse(retained.isReadPermission)
        assertTrue(resolver.persistedUriPermissions.single { it.uri == foreign }.isReadPermission)
        access.takeRead(tree)
        val taken = resolver.persistedUriPermissions.single { it.uri.toString() == tree }
        assertTrue(taken.isReadPermission); assertFalse(taken.isWritePermission)
    }

    @Test fun `cancelled native root query cancels provider signal and closes late cursor`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val cursor = folder("Kitaplar")
        var cancelled = false
        provider { _, _, signal ->
            signal!!.setOnCancelListener { cancelled = true; release.countDown() }
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            cursor
        }
        val job = async(Dispatchers.IO) { AndroidBookFolderAccess(resolver).inspect(tree) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(cancelled); assertTrue(job.isCancelled); assertTrue(cursor.isClosed)
            assertTrue(resolver.persistedUriPermissions.isEmpty())
        } finally { release.countDown(); job.cancelAndJoin() }
    }

    private fun folder(name: String?, mime: String = DocumentsContract.Document.MIME_TYPE_DIR) =
        MatrixCursor(columns).apply { addRow(arrayOf<Any?>(name, mime)) }
    private fun provider(query: (Uri, Array<String>?, CancellationSignal?) -> Cursor?): Provider {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return Provider(query).also {
            it.attachInfo(context, ProviderInfo().apply { authority = this@AndroidBookFolderAccessTest.authority })
            ShadowContentResolver.registerProviderInternal(authority, it)
        }
    }
    private class Provider(val action: (Uri, Array<String>?, CancellationSignal?) -> Cursor?) : ContentProvider() {
        var queries = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
            error("Cancellation-aware root query expected")
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?, signal: CancellationSignal?): Cursor? {
            queries++
            return action(uri, projection?.map { it }?.toTypedArray(), signal)
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No writes")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("No writes")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("No writes")
    }
}
