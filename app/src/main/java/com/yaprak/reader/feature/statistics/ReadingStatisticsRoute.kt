package com.yaprak.reader.feature.statistics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ReadingStatisticsRoute(model: ReadingStatisticsViewModel, onBack: () -> Unit) {
    ReadingStatisticsRoute(model, goalModel = null, onBack = onBack)
}

@Composable
internal fun ReadingStatisticsRoute(model: ReadingStatisticsViewModel,
    goalModel: DailyReadingGoalViewModel?, onBack: () -> Unit,
    completionModel: BookCompletionViewModel? = null, weeklyModel: WeeklyReadingGoalViewModel? = null) {
    var historyOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val state by model.state.collectAsStateWithLifecycle()
    val weeklyState = weeklyModel?.state?.collectAsStateWithLifecycle()?.value
    val goalState = goalModel?.state?.collectAsStateWithLifecycle()?.value
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val owner = remember(model, goalModel, weeklyModel, lifecycle) { Any() }
    fun foreground() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun mayAct() = !historyOpen && model.isLifecycleOwnerActive(owner) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun mayActOnGoal() = mayAct() && weeklyModel?.state?.value?.editor == null && weeklyModel?.state?.value?.saving != true && goalModel?.isLifecycleOwnerActive(owner) == true
    fun mayActOnWeekly() = mayAct() && goalModel?.state?.value?.editor == null && goalModel?.state?.value?.saving != true && weeklyModel?.isLifecycleOwnerActive(owner) == true
    DisposableEffect(model, goalModel, weeklyModel, lifecycle, context) {
        model.attachLifecycleOwner(owner)
        weeklyModel?.attachLifecycleOwner(owner)
        goalModel?.attachLifecycleOwner(owner)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    model.setLifecycleOwnerActive(owner, true)
                    weeklyModel?.setLifecycleOwnerActive(owner, true)
                    goalModel?.setLifecycleOwnerActive(owner, true)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    model.setLifecycleOwnerActive(owner, false)
                    weeklyModel?.setLifecycleOwnerActive(owner, false)
                    goalModel?.setLifecycleOwnerActive(owner, false)
                }
                else -> Unit
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (mayAct() && intent?.action in setOf(Intent.ACTION_DATE_CHANGED,
                        Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) model.refreshDate(owner)
            }
        }
        lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        model.setLifecycleOwnerActive(owner, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        weeklyModel?.setLifecycleOwnerActive(owner, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        goalModel?.setLifecycleOwnerActive(owner, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            context.unregisterReceiver(receiver)
            lifecycle.removeObserver(observer)
            model.detachLifecycleOwner(owner)
            weeklyModel?.detachLifecycleOwner(owner)
            goalModel?.detachLifecycleOwner(owner)
        }
    }
    ReadingStatisticsScreen(state, onRetry = { if (mayAct() && goalModel?.state?.value?.editor == null && weeklyModel?.state?.value?.editor == null) model.retry(it) },
        onCompletionHistory = completionModel?.let { { if (mayAct() && goalModel?.state?.value?.editor == null && weeklyModel?.state?.value?.editor == null && goalModel?.state?.value?.saving != true && weeklyModel?.state?.value?.saving != true) historyOpen = true } },
        onBack = { if (mayAct() && goalModel?.state?.value?.editor == null && weeklyModel?.state?.value?.editor == null) onBack() }, goalState = goalState,
        onGoalOpen = goalModel?.let { goal -> { revision: Long -> if (mayActOnGoal()) goal.open(owner, revision) } },
        onGoalRetry = goalModel?.let { goal -> { revision: Long -> if (mayActOnGoal()) goal.retry(owner, revision) } },
        weeklyState = weeklyState,
        onWeeklyOpen = weeklyModel?.let { goal -> { revision: Long -> if (mayActOnWeekly()) goal.open(owner, revision) } },
        onWeeklyRetry = weeklyModel?.let { goal -> { revision: Long -> if (mayActOnWeekly()) goal.retry(owner, revision) } })
    if (historyOpen && completionModel != null) {
        val history by completionModel.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
        BookCompletionDialog(history, completionModel::retry, { historyOpen = false }, ::foreground)
    }
    goalState?.editor?.let { draft ->
        DailyReadingGoalDialog(draft, goalState.saving, goalState.writeFailed,
            isCurrent = { mayActOnGoal() && goalModel?.state?.value?.editor?.requestId == draft.requestId },
            onEnabled = { id, revision, value -> if (mayActOnGoal()) goalModel?.editEnabled(owner, id, revision, value) },
            onMinutes = { id, revision, value -> if (mayActOnGoal()) goalModel?.editMinutes(owner, id, revision, value) },
            onSave = { id, revision -> if (mayActOnGoal()) goalModel?.save(owner, id, revision) },
            onDismiss = { id, revision -> if (mayActOnGoal()) goalModel?.dismiss(owner, id, revision) },
            enabled = goalState.active && goalState.known)
    }
    weeklyState?.editor?.let { draft ->
        WeeklyReadingGoalDialog(draft, weeklyState.saving, weeklyState.writeFailed,
            isCurrent = { mayActOnWeekly() && weeklyModel?.state?.value?.editor?.requestId == draft.requestId },
            onEnabled = { id, revision, value -> if (mayActOnWeekly()) weeklyModel?.editEnabled(owner, id, revision, value) },
            onMinutes = { id, revision, value -> if (mayActOnWeekly()) weeklyModel?.editMinutes(owner, id, revision, value) },
            onSave = { id, revision -> if (mayActOnWeekly()) weeklyModel?.save(owner, id, revision) },
            onDismiss = { id, revision -> if (mayActOnWeekly()) weeklyModel?.dismiss(owner, id, revision) },
            enabled = weeklyState.active && weeklyState.known)
    }
}
