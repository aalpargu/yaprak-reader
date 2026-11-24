package com.yaprak.reader.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.scale
import com.yaprak.reader.MainActivity
import com.yaprak.reader.R
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.core.StartupState
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.feature.library.mostRecentlyReadBook
import com.yaprak.reader.feature.reader.ReaderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the widget shows; null book means "nothing read yet". Pure, so it is testable without a launcher. */
internal data class ContinueReadingWidgetContent(val book: Book?, val percent: Int?) {
    val percentText: String? get() = percent?.takeIf { it in 0..100 }?.let { "%$it okundu" }
}

internal fun continueReadingWidgetContent(books: List<Book>, progress: Map<String, Int>): ContinueReadingWidgetContent {
    val book = mostRecentlyReadBook(books)
    return ContinueReadingWidgetContent(book, book?.let { progress[it.id] })
}

/**
 * Home screen "Devam et": the last book's cover, title and progress. Only the book id travels in
 * the tap intent; the reader validates it exactly like the launcher shortcut. Refreshed when a book
 * is opened or left, never on a timer.
 */
class ContinueReadingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refreshAsync(context)

    companion object {
        /** Cheap when no widget is placed: nothing is read. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val ids = runCatching { AppWidgetManager.getInstance(app)
                .getAppWidgetIds(ComponentName(app, ContinueReadingWidget::class.java)) }.getOrNull()
            if (ids == null || ids.isEmpty()) return
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { update(app) }
        }

        private fun refreshAsync(context: Context) {
            val app = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { update(app) }
        }

        private suspend fun update(app: Context) {
            val container = (app as? YaprakApplication)?.container ?: return
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, ContinueReadingWidget::class.java))
            if (ids.isEmpty()) return
            val content = try {
                val ready = withTimeoutOrNull(20_000) { container.startup.state.first { it != StartupState.Loading } }
                if (ready != StartupState.Ready) ContinueReadingWidgetContent(null, null)
                else continueReadingWidgetContent(container.library.observeBooks().first(),
                    container.library.observeProgress().first())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ContinueReadingWidgetContent(null, null) }
            val cover = content.book?.let { book ->
                try { container.covers.load(book)?.let(::widgetSized) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            manager.updateAppWidget(ids, views(app, content, cover))
        }

        /** RemoteViews bitmaps travel through binder; keep them small. */
        private fun widgetSized(bitmap: Bitmap): Bitmap {
            val maxHeight = 240
            if (bitmap.height <= maxHeight) return bitmap
            val width = (bitmap.width * maxHeight.toFloat() / bitmap.height).toInt().coerceAtLeast(1)
            return bitmap.scale(width, maxHeight)
        }

        private fun views(app: Context, content: ContinueReadingWidgetContent, cover: Bitmap?) =
            RemoteViews(app.packageName, R.layout.widget_continue_reading).apply {
                val library = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val book = content.book
                if (book == null) {
                    setTextViewText(R.id.widget_title, app.getString(R.string.widget_empty_title))
                    setViewVisibility(R.id.widget_label, View.GONE)
                    setViewVisibility(R.id.widget_progress, View.GONE)
                    setViewVisibility(R.id.widget_percent, View.GONE)
                    setViewVisibility(R.id.widget_continue, View.GONE)
                    setImageViewResource(R.id.widget_cover, R.drawable.ic_book)
                    val open = PendingIntent.getActivity(app, 1, library, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    setOnClickPendingIntent(R.id.widget_root, open)
                    return@apply
                }
                setViewVisibility(R.id.widget_label, View.VISIBLE)
                setViewVisibility(R.id.widget_continue, View.VISIBLE)
                setTextViewText(R.id.widget_title, book.title)
                val percent = content.percentText
                setViewVisibility(R.id.widget_progress, if (percent != null) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.widget_percent, if (percent != null) View.VISIBLE else View.GONE)
                if (percent != null) {
                    setProgressBar(R.id.widget_progress, 100, content.percent!!, false)
                    setTextViewText(R.id.widget_percent, percent)
                }
                if (cover != null) setImageViewBitmap(R.id.widget_cover, cover)
                else setImageViewResource(R.id.widget_cover, R.drawable.ic_book)
                // Back from the reader returns to the library, as with the launcher shortcut.
                val reader = ReaderActivity.intent(app, book.id).setAction(Intent.ACTION_VIEW)
                val open = PendingIntent.getActivities(app, 2, arrayOf(library, reader),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                setOnClickPendingIntent(R.id.widget_root, open)
                setOnClickPendingIntent(R.id.widget_continue, open)
            }
    }
}
