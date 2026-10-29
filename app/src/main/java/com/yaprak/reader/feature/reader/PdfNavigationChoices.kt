package com.yaprak.reader.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import com.yaprak.reader.R
import com.yaprak.reader.domain.settings.PdfNavigationMode

private class PdfNavigationChoiceLease { var active = true }

@Composable
internal fun PdfNavigationChoices(current: PdfNavigationMode, enabled: Boolean, choose: (PdfNavigationMode) -> Unit) {
    // Disposed old chrome actions cannot select a new mode after ownership/recomposition changes.
    key(current, enabled, choose) {
        val lease = remember { PdfNavigationChoiceLease() }
        DisposableEffect(lease) { onDispose { lease.active = false } }
        Column(Modifier.fillMaxWidth().selectableGroup().testTag("pdf-navigation-choices")) {
            Text(stringResource(R.string.pdf_navigation_choices))
            for (mode in PdfNavigationMode.entries) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp).testTag("pdf-navigation-" + mode.name)
                    .selectable(selected = current == mode, enabled = enabled, role = Role.RadioButton,
                        onClick = { if (lease.active && enabled && current != mode) choose(mode) }),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = current == mode, onClick = null, enabled = enabled)
                    Text(stringResource(when (mode) {
                        PdfNavigationMode.PAGED -> R.string.pdf_navigation_paged
                        PdfNavigationMode.VERTICAL_SCROLL -> R.string.pdf_navigation_vertical
                        PdfNavigationMode.HORIZONTAL_SCROLL -> R.string.pdf_navigation_horizontal
                    }))
                }
            }
        }
    }
}
