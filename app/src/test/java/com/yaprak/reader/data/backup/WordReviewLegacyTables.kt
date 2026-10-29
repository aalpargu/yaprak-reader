package com.yaprak.reader.data.backup

import org.json.JSONObject

/** Construct a genuine pre-v11 shape; forged-header rejection tests keep their new tables. */
internal fun stripWordReviewTables(tables: JSONObject) {
    tables.remove("word_schedules"); tables.remove("word_review_events")
    val words = tables.getJSONArray("saved_words")
    for (i in 0 until words.length()) words.getJSONObject(i).remove("revision")
}
