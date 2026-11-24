package com.yaprak.reader.feature.reader

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.yaprak.reader.MainActivity
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.Book

/**
 * Launcher long-press shortcut "Devam et: <kitap>" for the most recently opened book.
 * Only the book id travels in the intent; the reader resolves and validates it as on any open.
 * A removed book simply opens the reader's normal "not found" state.
 */
internal object ContinueReadingShortcut {
    private const val ID = "continue-reading"

    fun publish(context: Context, book: Book) {
        val app = context.applicationContext
        val library = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        // Back from the reader returns to the library rather than the launcher.
        val reader = ReaderActivity.intent(app, book.id).setAction(Intent.ACTION_VIEW)
        val label = book.title.take(40)
        val shortcut = ShortcutInfoCompat.Builder(app, ID)
            .setShortLabel(label.ifBlank { app.getString(R.string.continue_reading_action) })
            .setLongLabel(app.getString(R.string.continue_reading_shortcut_long, book.title).take(100))
            .setIcon(IconCompat.createWithResource(app, R.mipmap.ic_launcher))
            .setIntents(arrayOf(library, reader))
            .build()
        // A launcher may reject or rate-limit shortcuts; reading must never depend on it.
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(app, shortcut) }
    }
}
