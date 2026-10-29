package com.yaprak.reader.data.backup

import org.json.JSONObject

/** Test fixture conversion only: archives 1..12 did not contain spacing fields. */
internal fun JSONObject.asPreSpacingSettings(): JSONObject = apply {
    remove("paragraphSpacingPercent")
    remove("wordSpacingPercent")
}

/** Fixture conversion only: formats 1..13 contained no per-book spacing table. */
internal fun JSONObject.asPreBookSpacingTables(): JSONObject = apply {
    getJSONObject("tables").remove("book_pdf_navigation_preferences")
    getJSONObject("tables").remove("book_cbz_view_preferences")
    getJSONObject("tables").remove("book_epub_reading_mode_overrides")
    getJSONObject("tables").remove("book_epub_text_alignment_overrides")
    getJSONObject("tables").remove("book_completion_events")
    getJSONObject("tables").remove("book_epub_spacing_overrides")
}
