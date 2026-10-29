package com.yaprak.reader.data.discovery

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yaprak.reader.domain.library.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.bookFolderPreferences by preferencesDataStore(name = "book_folder")

/** Single owned atomic record. Pending ownership is durable before the OS grant is acquired.
 * A pending old release is committed with the new selection, never confused with rollback.
 */
internal data class BookFolderRecord(
    val snapshot: BookFolderSnapshot = BookFolderSnapshot("00000000-0000-0000-0000-000000000000", null),
    val ownsReadGrant: Boolean = false,
    val pendingTake: String? = null,
    val pendingRelease: String? = null,
) {
    val valid: Boolean get() = snapshot.valid && (!ownsReadGrant || snapshot.folder != null) &&
        pendingTake?.let(::validBookFolderUri) != false && pendingRelease?.let(::validBookFolderUri) != false &&
        (pendingTake == null || pendingRelease == null) && (pendingRelease == null || pendingRelease != snapshot.folder?.treeUri)
}

internal class BookFolderRecords(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.bookFolderPreferences)
    val records = store.data.map { values ->
        val raw = values.asMap()[recordKey]
        if (raw == null) BookFolderRecord() else {
            if (raw !is String) throw BookFolderException(BookFolderFailure.SOURCE)
            decode(raw)
        }
    }
    suspend fun read() = records.first()
    suspend fun write(record: BookFolderRecord) {
        require(record.valid)
        val json = JSONObject().put("version", 2).put("revision", record.snapshot.revision)
            .put("uri", record.snapshot.folder?.treeUri ?: JSONObject.NULL)
            .put("name", record.snapshot.folder?.displayName ?: JSONObject.NULL)
            .put("owns", record.ownsReadGrant).put("take", record.pendingTake ?: JSONObject.NULL)
            .put("release", record.pendingRelease ?: JSONObject.NULL).put("automatic", record.snapshot.automaticScan).toString()
        require(json.length <= 65_536)
        store.edit { it[recordKey] = json }
    }
    private fun decode(raw: String): BookFolderRecord {
        if (raw.length > 65_536) throw BookFolderException(BookFolderFailure.SOURCE)
        try {
            val json = JSONObject(raw)
            val version = json.get("version")
            if (version != 1 && version != 2) throw BookFolderException(BookFolderFailure.SOURCE)
            val expectedKeys = setOf("version", "revision", "uri", "name", "owns", "take", "release") +
                if (version == 2) setOf("automatic") else emptySet()
            if (json.keys().asSequence().toSet() != expectedKeys || json.get("owns") !is Boolean ||
                (version == 2 && json.get("automatic") !is Boolean)) throw BookFolderException(BookFolderFailure.SOURCE)
            fun text(key: String): String? = json.get(key).let { if (it === JSONObject.NULL) null else it as? String
                ?: throw BookFolderException(BookFolderFailure.SOURCE) }
            val uri = text("uri"); val name = text("name")
            if (uri == null && name != null) throw BookFolderException(BookFolderFailure.SOURCE)
            val snapshot = BookFolderSnapshot(text("revision") ?: throw BookFolderException(BookFolderFailure.SOURCE),
                uri?.let { SavedBookFolder(it, name) }, automaticScan = version == 2 && json.getBoolean("automatic"))
            return BookFolderRecord(snapshot, json.getBoolean("owns"), text("take"), text("release")).also {
                if (!it.valid) throw BookFolderException(BookFolderFailure.SOURCE)
            }
        } catch (failure: BookFolderException) { throw failure }
        catch (_: Exception) { throw BookFolderException(BookFolderFailure.SOURCE) }
    }
    companion object { val recordKey = stringPreferencesKey("book_folder_record_v1") }
}
