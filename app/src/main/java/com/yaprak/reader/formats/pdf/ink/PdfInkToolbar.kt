package com.yaprak.reader.formats.pdf.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import com.yaprak.reader.domain.pdfink.PdfInkMode

private fun PdfHighlightColor.label() = when (this) {
    PdfHighlightColor.YELLOW -> "Sarı"
    PdfHighlightColor.GREEN -> "Yeşil"
    PdfHighlightColor.BLUE -> "Mavi"
    PdfHighlightColor.PINK -> "Pembe"
}

/** Shown only while the pen is on. "Bitti" (or Back) always returns to normal reading. */
@Composable
internal fun PdfInkToolbar(controller: PdfInkController, atTop: Boolean, onMove: () -> Unit, modifier: Modifier = Modifier) {
    val tool by controller.tool.collectAsState()
    val canUndo by controller.canUndo.collectAsState()
    val notice by controller.notice.collectAsState()
    Surface(modifier.padding(12.dp).testTag("pdf-ink-toolbar"), shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            notice?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(controller::dismissNotice) { Text("Kapat") }
                }
            }
            Text(when {
                tool.eraser -> "Silmek için çizime dokun"
                tool.mode == PdfInkMode.ALIGNED -> "Satırın üstünden sürükle · iki parmakla kaydır"
                else -> "Serbestçe boya · iki parmakla kaydır"
            }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PdfHighlightColor.entries.forEach { color ->
                    val chosen = color == tool.color && !tool.eraser
                    Box(Modifier.size(36.dp).clip(CircleShape).background(color.inkColor())
                        .border(if (chosen) 3.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { controller.select(color) }
                        .semantics { contentDescription = "${color.label()} kalem"; role = Role.RadioButton; selected = chosen }
                        .testTag("pdf-ink-color-${color.name}"))
                }
                if (controller.textAvailable) {
                    FilterChip(tool.mode == PdfInkMode.ALIGNED && !tool.eraser, { controller.select(PdfInkMode.ALIGNED) },
                        { Text("Hizalı") }, Modifier.testTag("pdf-ink-aligned"))
                    FilterChip(tool.mode == PdfInkMode.FREEHAND && !tool.eraser, { controller.select(PdfInkMode.FREEHAND) },
                        { Text("Serbest") }, Modifier.testTag("pdf-ink-freehand"))
                }
                FilterChip(tool.eraser, controller::toggleEraser, { Text("Silgi") },
                    Modifier.testTag("pdf-ink-eraser"), leadingIcon = { Icon(Icons.Default.Clear, null) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(controller::undo, enabled = canUndo, modifier = Modifier.testTag("pdf-ink-undo")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Geri al")
                }
                Spacer(Modifier.weight(1f))
                // The bar covers part of the page; it can move to the other edge.
                IconButton(onMove, Modifier.testTag("pdf-ink-move")) {
                    Icon(if (atTop) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        if (atTop) "Araç çubuğunu alta taşı" else "Araç çubuğunu üste taşı")
                }
                Button(controller::exit, Modifier.testTag("pdf-ink-done")) {
                    Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text("Bitti")
                }
            }
        }
    }
}
