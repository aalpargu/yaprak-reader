package com.yaprak.reader.feature.library

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaprak.reader.domain.library.Book
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
internal fun LibraryBookCover(
    book: Book,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    coverLoader: (suspend (Book) -> Bitmap?)? = null,
) {
    // Metadata edits do not change the underlying cover request. A different file or
    // loader gets fresh state immediately, and disposes the previous request.
    val cover = remember(book.id, book.sha256, book.format, book.storageName,
        book.customCover?.id, book.customCover?.sha256, book.customCover?.storageName, coverLoader) {
        mutableStateOf<Bitmap?>(null)
    }
    LaunchedEffect(cover) {
        val loader = coverLoader ?: return@LaunchedEffect
        val bitmap = try {
            loader(book)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        currentCoroutineContext().ensureActive()
        cover.value = bitmap
    }
    val bitmap = cover.value
    if (bitmap == null) {
        TypographicBookCover(book, modifier, compact)
    } else {
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** Typographic jackets are presentation only, never represented as embedded book covers. */
@Composable
private fun TypographicBookCover(book: Book, modifier: Modifier = Modifier, compact: Boolean = false) {
    val palette = listOf(Color(0xFF34574E), Color(0xFF805844), Color(0xFF4F5F73), Color(0xFF706046))
    val color = palette[(book.sha256.firstOrNull()?.digitToIntOrNull(16) ?: 0) % palette.size]
    Box(modifier.background(color)) {
        Canvas(Modifier.fillMaxSize()) {
            drawLine(Color.White.copy(alpha = 0.18f), Offset(10.dp.toPx(), 0f), Offset(10.dp.toPx(), size.height), 1.dp.toPx())
            drawCircle(Color.White.copy(alpha = 0.055f), size.width * 0.6f, Offset(size.width, size.height * 0.9f))
            drawCircle(Color.White.copy(alpha = 0.055f), size.width * 0.43f, Offset(size.width, size.height * 0.9f))
        }
        if (compact) {
            Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 9.dp),
                verticalArrangement = Arrangement.SpaceBetween) {
                Text(book.format.name, color = Color(0xFFFFF1D6),
                    style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                Text(book.title, fontFamily = FontFamily.Serif, fontSize = 12.sp, lineHeight = 14.sp,
                    color = Color(0xFFFFF6E5), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(start = 26.dp, end = 18.dp, top = 20.dp, bottom = 18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(book.format.name, color = Color(0xFFFFF1D6), style = MaterialTheme.typography.labelSmall)
                    Text("—", color = Color.White.copy(alpha = 0.6f))
                }
                Text(book.title, fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 29.sp, color = Color(0xFFFFF6E5), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("YAPRAK", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
