package com.yaprak.reader.data.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yaprak.reader.MainActivity
import com.yaprak.reader.R
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.core.StartupState
import com.yaprak.reader.domain.statistics.nextReadingReminderAt
import com.yaprak.reader.domain.statistics.readingReminderText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One inexact daily alarm (no exact-alarm permission). The alarm only wakes this receiver; whether
 * a notification appears is decided then, from today's measured reading time and the daily goal.
 */
object ReadingReminderAlarms {
    const val CHANNEL = "reading_reminder"
    private const val NOTIFICATION_ID = 4101
    internal const val ACTION_REMIND = "com.yaprak.reader.action.READING_REMINDER"
    private const val WINDOW_MILLIS = 10 * 60_000L

    fun schedule(context: Context, minuteOfDay: Int?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        alarms.cancel(pending)
        if (minuteOfDay == null) return
        val at = nextReadingReminderAt(now, minuteOfDay, zone).toEpochMilli()
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MILLIS, pending)
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReadingReminderReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    internal fun notify(context: Context, text: String) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Okuma hatırlatıcısı", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_book)
            .setContentTitle("Okuma zamanı")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
        catch (_: SecurityException) { /* Permission withdrawn between the check and the post. */ }
    }
}

/** Alarm, boot, time and update events. Book content is never read here, only durations. */
class ReadingReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? YaprakApplication ?: return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = app.container
                val minute = container.readingReminder.reminderMinuteOfDay.first()
                // Always arm the next day first, so a failure below never ends the reminders.
                ReadingReminderAlarms.schedule(app, minute)
                if (minute == null || intent.action != ReadingReminderAlarms.ACTION_REMIND) return@launch
                val ready = withTimeoutOrNull(20_000) { container.startup.state.first { it != StartupState.Loading } }
                if (ready != StartupState.Ready) return@launch // Unknown progress: stay quiet rather than guess.
                val today = LocalDate.now(ZoneId.systemDefault()).toString()
                val readToday = container.readingDuration.observe().first().days
                    .firstOrNull { it.localDate == today }?.elapsedMillis ?: 0L
                val text = readingReminderText(readToday, container.readingGoal.dailyMinutes.first()) ?: return@launch
                ReadingReminderAlarms.notify(app, text)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Quiet on any read failure; the next day is already armed. */ }
            finally { result.finish() }
        }
    }
}
