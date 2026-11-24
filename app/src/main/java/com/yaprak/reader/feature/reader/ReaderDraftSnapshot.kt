package com.yaprak.reader.feature.reader

import android.os.Bundle
import com.yaprak.reader.domain.bookmarks.Bookmark
import com.yaprak.reader.domain.notes.BookNote
import com.yaprak.reader.domain.vocabulary.SavedWord
import java.nio.ByteBuffer
import java.security.MessageDigest

internal const val MAX_READER_DRAFT_FIELD_LENGTH = 8192

/** User input only. A restored form must resolve its edited row again; it contains no command or source lease. */
internal data class ReaderDraftSnapshot(
    val query: String,
    val sort: String,
    val draft: String = "",
    val secondaryDraft: String = "",
    val editId: String? = null,
    val expectedFingerprint: String? = null,
    val editDraft: String = "",
    val secondaryEditDraft: String = "",
    val listIndex: Int = 0,
    val listOffset: Int = 0,
    val recoveredDraft: String = "",
    val secondaryRecoveredDraft: String = "",
) {
    private fun valid(): Boolean = query.length <= 200 && sort.length in 1..64 &&
        listOf(draft, secondaryDraft, editDraft, secondaryEditDraft, recoveredDraft, secondaryRecoveredDraft)
            .let { fields -> fields.all { it.length <= MAX_READER_DRAFT_FIELD_LENGTH } && fields.sumOf { it.length } <= 32_768 } &&
        listIndex in 0..100_000 && listOffset in 0..100_000 &&
        ((editId == null && expectedFingerprint == null) ||
            (editId != null && editId.length in 1..128 && expectedFingerprint?.matches(Regex("[0-9a-f]{64}")) == true))

    fun toBundle(): Bundle? = if (!valid()) null else Bundle().apply {
        putInt("version", 2)
        putString("query", query); putString("sort", sort)
        putString("draft", draft); putString("secondaryDraft", secondaryDraft)
        putString("editId", editId); putString("expectedFingerprint", expectedFingerprint)
        putString("editDraft", editDraft); putString("secondaryEditDraft", secondaryEditDraft)
        putInt("listIndex", listIndex); putInt("listOffset", listOffset)
        putString("recoveredDraft", recoveredDraft); putString("secondaryRecoveredDraft", secondaryRecoveredDraft)
    }

    companion object {
        private val originalKeys = setOf("version", "query", "sort", "draft", "secondaryDraft", "editId",
            "expectedFingerprint", "editDraft", "secondaryEditDraft", "listIndex", "listOffset")
        private val keys = originalKeys + setOf("recoveredDraft", "secondaryRecoveredDraft")

        @Suppress("DEPRECATION")
        fun fromBundle(bundle: Bundle?): ReaderDraftSnapshot? {
            bundle ?: return null
            return runCatching {
                val version = bundle.get("version")
                if (when (version) { 1 -> bundle.keySet() != originalKeys; 2 -> bundle.keySet() != keys; else -> true }) return null
                val id = bundle.get("editId")
                val fingerprint = bundle.get("expectedFingerprint")
                if ((id != null && id !is String) || (fingerprint != null && fingerprint !is String)) return null
                ReaderDraftSnapshot(
                    bundle.get("query") as? String ?: return null,
                    bundle.get("sort") as? String ?: return null,
                    bundle.get("draft") as? String ?: return null,
                    bundle.get("secondaryDraft") as? String ?: return null,
                    id as String?, fingerprint as String?,
                    bundle.get("editDraft") as? String ?: return null,
                    bundle.get("secondaryEditDraft") as? String ?: return null,
                    bundle.get("listIndex") as? Int ?: return null,
                    bundle.get("listOffset") as? Int ?: return null,
                    if (version == 1) "" else bundle.get("recoveredDraft") as? String ?: return null,
                    if (version == 1) "" else bundle.get("secondaryRecoveredDraft") as? String ?: return null,
                ).takeIf { it.valid() }
            }.getOrNull()
        }
    }
}

/** Length framing prevents text containing separators from matching a different set of fields. */
internal fun readerDraftFingerprint(vararg parts: String): String {
    val hash = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(Charsets.UTF_8)
        hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        hash.update(bytes)
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

internal fun readerDraftFingerprint(entry: BookNote): String = readerDraftFingerprint(
    entry.id, entry.createdAt.toString(), entry.pdfPageIndex?.toString().orEmpty(), entry.text)

internal fun readerDraftFingerprint(entry: SavedWord): String = readerDraftFingerprint(
    entry.id, entry.createdAt.toString(), entry.revision.toString(), entry.english, entry.turkish)

internal fun readerDraftFingerprint(entry: Bookmark): String = readerDraftFingerprint(
    entry.id, entry.createdAt.toString(), entry.target.label, entry.target.location.locatorVersion.toString(),
    entry.target.location.resourceHref, entry.target.location.locatorJson)
