package com.yaprak.reader.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Position scrubber model. [value] and [max] are in the reader's own unit (page index or book percent);
 * [onSeek] is called once when the user releases the thumb, never while dragging.
 */
internal class ReaderSeek(val value: Int, val max: Int, val label: (Int) -> String, val onSeek: (Int) -> Unit)

/** Compact controls carry display values and user commands, never a reader session or repository. */
@Composable
internal fun ReaderTopBar(
    title: String, onBack: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    onBookmark: (() -> Unit)? = null,
) {
    Surface(modifier.fillMaxWidth().testTag("reader-top-bar"), tonalElevation = 3.dp, shadowElevation = 2.dp) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kitaplığa dön") }
            Text(title, Modifier.weight(1f).padding(horizontal = 4.dp), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onSearch != null) IconButton(onSearch) { Icon(Icons.Default.Search, contentDescription = "Kitapta ara") }
            if (onBookmark != null) IconButton(onBookmark, Modifier.testTag("reader-quick-bookmark")) {
                Icon(Icons.Default.Star, contentDescription = "Bu sayfaya yer imi ekle")
            }
            // Tapping the page also hides the controls; this button keeps it discoverable and accessible.
            IconButton(onHide, Modifier.testTag("reader-hide")) {
                Icon(Icons.Default.Close, contentDescription = "Araçları gizle")
            }
        }
    }
}

@Composable
internal fun ReaderBottomBar(
    onContent: () -> Unit, onSearch: (() -> Unit)?, onAppearance: () -> Unit, onTools: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onPosition: (() -> Unit)?,
    pageLabel: String?, positionPercent: Int?, modifier: Modifier = Modifier,
    positionKind: ReaderPanelKind = ReaderPanelKind.POSITION,
    focusTarget: @Composable (ReaderPanelKind) -> Modifier = { Modifier },
    seek: ReaderSeek? = null,
    remainingTime: String? = null,
    chapterTitle: String? = null,
    positionDescription: String? = null,
) {
    Surface(modifier.fillMaxWidth().testTag("reader-bottom-bar"), tonalElevation = 3.dp, shadowElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            // Position row: previous · scrubber (or position label) · next
            var dragging by remember(seek?.max) { mutableStateOf<Float?>(null) }
            val shownValue = dragging?.roundToInt() ?: seek?.value
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onPrevious) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Önceki") }
                if (seek != null && seek.max > 0) {
                    Slider(
                        value = (dragging ?: seek.value.toFloat()).coerceIn(0f, seek.max.toFloat()),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            val target = dragging?.roundToInt()
                            dragging = null
                            if (target != null && target != seek.value) seek.onSeek(target.coerceIn(0, seek.max))
                        },
                        valueRange = 0f..seek.max.toFloat(),
                        modifier = Modifier.weight(1f).testTag("reader-seek")
                            .semantics { contentDescription = "Kitap konumu" },
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Sonraki") }
            }
            val positionText = when {
                dragging != null && seek != null && shownValue != null -> seek.label(shownValue)
                else -> listOfNotNull(pageLabel, positionPercent?.let { "%$it" }, remainingTime?.let { "kalan: $it" }).joinToString(" · ")
            }
            if (chapterTitle != null) Text(chapterTitle, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("reader-chapter-title"),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (positionText.isNotBlank() || onPosition != null) {
                TextButton({ onPosition?.invoke() }, Modifier.fillMaxWidth().padding(horizontal = 48.dp).heightIn(min = 32.dp)
                    .then(focusTarget(positionKind)).testTag("reader-position")
                    .then(if (positionDescription != null && dragging == null) Modifier.semantics { contentDescription = positionDescription } else Modifier),
                    enabled = onPosition != null,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                    Text(positionText.ifBlank { "Konuma git" }, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BarAction("İçerik", Icons.AutoMirrored.Filled.List, onContent,
                    Modifier.weight(1f).then(focusTarget(ReaderPanelKind.CONTENT)))
                BarAction("Ara", Icons.Default.Search, { onSearch?.invoke() },
                    Modifier.weight(1f).then(focusTarget(ReaderPanelKind.SEARCH)), enabled = onSearch != null)
                BarAction("Görünüm", null, onAppearance,
                    Modifier.weight(1f).then(focusTarget(ReaderPanelKind.APPEARANCE)), glyph = "Aa")
                BarAction("Diğer", Icons.Default.MoreVert, onTools,
                    Modifier.weight(1f).then(focusTarget(ReaderPanelKind.TOOLS)).testTag("reader-more"))
            }
        }
    }
}

/** Immediate per-book font size and theme, applied as soon as they are tapped. */
internal class ReaderQuickAppearance(
    val fontSizePercent: Int, val onFontSize: (Int) -> Unit,
    val theme: com.yaprak.reader.domain.settings.ReadingTheme,
    val onTheme: (com.yaprak.reader.domain.settings.ReadingTheme) -> Unit,
    val busy: Boolean,
)

@Composable
internal fun ReaderQuickAppearanceControls(quick: ReaderQuickAppearance) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Yazı boyutu", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            OutlinedButton({ quick.onFontSize((quick.fontSizePercent - 10).coerceAtLeast(75)) },
                enabled = !quick.busy && quick.fontSizePercent > 75,
                modifier = Modifier.semantics { contentDescription = "Yazıyı küçült" }) {
                Text("A", fontSize = 13.sp)
            }
            Text("%${quick.fontSizePercent}", Modifier.widthIn(min = 56.dp).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            OutlinedButton({ quick.onFontSize((quick.fontSizePercent + 10).coerceAtMost(200)) },
                enabled = !quick.busy && quick.fontSizePercent < 200,
                modifier = Modifier.semantics { contentDescription = "Yazıyı büyüt" }) {
                Text("A", fontSize = 20.sp)
            }
        }
        val themes = listOf(
            com.yaprak.reader.domain.settings.ReadingTheme.LIGHT to (androidx.compose.ui.graphics.Color(0xFFFFFFFF) to "Açık"),
            com.yaprak.reader.domain.settings.ReadingTheme.SEPIA to (androidx.compose.ui.graphics.Color(0xFFF4ECD8) to "Sepya"),
            com.yaprak.reader.domain.settings.ReadingTheme.DARK to (androidx.compose.ui.graphics.Color(0xFF2B2B2B) to "Koyu"),
            com.yaprak.reader.domain.settings.ReadingTheme.AMOLED to (androidx.compose.ui.graphics.Color(0xFF000000) to "Siyah"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            themes.forEach { (theme, look) ->
                val (color, label) = look
                val selected = quick.theme == theme
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(onClick = { quick.onTheme(theme) }, enabled = !quick.busy, shape = androidx.compose.foundation.shape.CircleShape,
                        color = color, border = androidx.compose.foundation.BorderStroke(if (selected) 3.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "$label tema" }) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Aa", color = if (theme == com.yaprak.reader.domain.settings.ReadingTheme.LIGHT ||
                                theme == com.yaprak.reader.domain.settings.ReadingTheme.SEPIA)
                                androidx.compose.ui.graphics.Color(0xFF222222) else androidx.compose.ui.graphics.Color(0xFFE6E6E6),
                                fontFamily = FontFamily.Serif)
                        }
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

/** One tappable menu row: icon, title and a short hint. Used by reader menus instead of bare text buttons. */
@Composable
internal fun ReaderMenuRow(
    title: String, supporting: String?, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth(), color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                // Wrap instead of ellipsizing: at large font scales a cut-off command name is unreadable.
                Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
                if (supporting != null) Text(supporting, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BarAction(
    label: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, glyph: String? = null,
) {
    TextButton(onClick, modifier.heightIn(min = 56.dp), enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) Icon(icon, contentDescription = null, Modifier.size(22.dp))
            else Text(glyph.orEmpty(), fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                lineHeight = 22.sp)
            // Wraps rather than clipping at large font scales.
            Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, fontSize = 11.sp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
