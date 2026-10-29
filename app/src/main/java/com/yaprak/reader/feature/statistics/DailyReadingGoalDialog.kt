package com.yaprak.reader.feature.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.R

@Composable
internal fun DailyReadingGoalDialog(
    draft: DailyReadingGoalDraft,
    saving: Boolean,
    writeFailed: Boolean,
    isCurrent: () -> Boolean,
    onEnabled: (String, Long, Boolean) -> Unit,
    onMinutes: (String, Long, String) -> Unit,
    onSave: (String, Long) -> Unit,
    onDismiss: (String, Long) -> Unit,
    enabled: Boolean = true,
) {
    key(draft.requestId) {
        val lease = remember { DailyReadingGoalDialogLease() }
        val gate by rememberUpdatedState(isCurrent)
        val liveDraft by rememberUpdatedState(draft)
        val liveSaving by rememberUpdatedState(saving)
        val liveEnabled by rememberUpdatedState(enabled)
        DisposableEffect(lease) { lease.active = true; onDispose { lease.active = false } }
        val request = draft.requestId
        val revision = draft.revision
        fun mayAct() = lease.active && gate() && liveDraft.requestId == request && liveDraft.revision == revision
        fun mayEdit() = mayAct() && !liveSaving && liveEnabled
        fun close() { if (mayAct() && !liveSaving) onDismiss(request, revision) }
        val bodyScroll = rememberScrollState()
        LaunchedEffect(writeFailed) { if (writeFailed && mayAct()) bodyScroll.scrollTo(0) }

        Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().height(minOf(maxHeight, 520.dp)),
                    shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                    Column {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(bodyScroll).padding(16.dp)
                            .testTag("daily-goal-body"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.daily_goal_title), Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.titleLarge)
                            if (writeFailed) Text(stringResource(R.string.daily_goal_save_failed), Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.daily_goal_help), Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth().testTag("daily-goal-enabled")
                                .toggleable(draft.enabled, enabled = enabled && !saving, role = Role.Switch,
                                    onValueChange = { if (mayEdit()) onEnabled(request, revision, it) }),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.daily_goal_enabled), Modifier.weight(1f).padding(end = 8.dp))
                                Switch(draft.enabled, onCheckedChange = null, enabled = enabled && !saving)
                            }
                            val label = stringResource(R.string.daily_goal_minutes_label)
                            Text(label, Modifier.fillMaxWidth())
                            OutlinedTextField(draft.minutesText,
                                onValueChange = { if (mayEdit()) onMinutes(request, revision, it) },
                                enabled = draft.enabled && enabled && !saving, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth().testTag("daily-goal-minutes")
                                    .semantics { contentDescription = label })
                            Text(stringResource(R.string.daily_goal_range), Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodySmall)
                            if (draft.enabled && !draft.valid) Text(stringResource(R.string.daily_goal_invalid),
                                Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                            if (saving) Text(stringResource(R.string.daily_goal_saving), Modifier.fillMaxWidth())
                            else if (!enabled) Text(stringResource(R.string.daily_goal_loading), Modifier.fillMaxWidth())
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                            .testTag("daily-goal-actions")) {
                            key(revision) {
                                TextButton(onClick = {
                                    if (mayEdit() && liveDraft.valid) onSave(request, revision)
                                }, enabled = enabled && !saving && draft.valid,
                                    modifier = Modifier.fillMaxWidth().testTag("daily-goal-save")) {
                                    Text(stringResource(R.string.daily_goal_save), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                }
                                TextButton(onClick = ::close, enabled = !saving,
                                    modifier = Modifier.fillMaxWidth().testTag("daily-goal-cancel")) {
                                    Text(stringResource(R.string.daily_goal_cancel), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class DailyReadingGoalDialogLease { var active = true }
