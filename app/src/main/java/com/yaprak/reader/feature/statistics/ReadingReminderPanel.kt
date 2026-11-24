package com.yaprak.reader.feature.statistics

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.statistics.ReadingReminderRepository
import com.yaprak.reader.domain.statistics.formatReminderTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ReadingReminderState(
    val loaded: Boolean = false,
    val minuteOfDay: Int? = null,
    val failed: Boolean = false,
    val saving: Boolean = false,
)

/** Device preference plus the alarm it arms; the alarm follows only a successfully saved value. */
internal class ReadingReminderViewModel(
    private val repository: ReadingReminderRepository,
    private val schedule: (Int?) -> Unit,
) : ViewModel() {
    private val mutable = MutableStateFlow(ReadingReminderState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                repository.reminderMinuteOfDay.collect { minute ->
                    mutable.update { it.copy(loaded = true, minuteOfDay = minute) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loaded = false, failed = true) } }
        }
    }

    fun save(minuteOfDay: Int?) {
        if (mutable.value.saving || !mutable.value.loaded) return
        mutable.update { it.copy(saving = true, failed = false) }
        viewModelScope.launch {
            try {
                repository.saveReminderMinuteOfDay(minuteOfDay)
                schedule(minuteOfDay)
                mutable.update { it.copy(saving = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(saving = false, failed = true) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingReminderPanel(model: ReadingReminderViewModel, canNotify: () -> Boolean) {
    val state by model.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var pendingMinute by remember { mutableStateOf<Int?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        pendingMinute?.let(model::save)
        pendingMinute = null
    }
    fun enable(minute: Int) {
        if (Build.VERSION.SDK_INT >= 33 && !canNotify()) {
            pendingMinute = minute
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else model.save(minute)
    }
    Column(Modifier.fillMaxWidth().testTag("statistics-reminder"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Okuma hatırlatıcısı", style = MaterialTheme.typography.titleMedium)
                Text(when {
                    state.failed && !state.loaded -> "Ayar okunamadı."
                    state.minuteOfDay == null -> "Kapalı"
                    else -> "Her gün ${formatReminderTime(state.minuteOfDay!!)} · bugün okumadıysan veya hedefe ulaşmadıysan"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(state.minuteOfDay != null, onCheckedChange = { on ->
                if (on) picking = true else model.save(null)
            }, enabled = state.loaded && !state.saving, modifier = Modifier.testTag("statistics-reminder-switch"))
        }
        if (state.minuteOfDay != null) TextButton({ picking = true }, enabled = state.loaded && !state.saving,
            modifier = Modifier.testTag("statistics-reminder-time")) { Text("Saati değiştir") }
        if (state.failed && state.loaded) Text("Hatırlatıcı kaydedilemedi.", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        if (permissionDenied || (state.minuteOfDay != null && !canNotify()))
            Text("Bildirim izni kapalı; hatırlatıcı görünmez. Telefon ayarlarından Yaprak bildirimlerini açabilirsin.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (picking) {
        val initial = state.minuteOfDay ?: (20 * 60)
        val time = rememberTimePickerState(initial / 60, initial % 60, is24Hour = true)
        AlertDialog(onDismissRequest = { picking = false },
            title = { Text("Hatırlatma saati") },
            text = { TimePicker(time) },
            confirmButton = { TextButton({ picking = false; enable(time.hour * 60 + time.minute) },
                Modifier.testTag("statistics-reminder-save")) { Text("Kaydet") } },
            dismissButton = { TextButton({ picking = false }) { Text("Vazgeç") } })
    }
}
