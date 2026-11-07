package com.sanghwan.medi

import android.Manifest
import android.annotation.SuppressLint
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
import java.time.LocalDate

/** 알람이 울릴 때 / 알림 버튼을 눌렀을 때 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val medId = i.getIntExtra("medId", -1)
        val time = i.getStringExtra("time") ?: return
        val date = i.getStringExtra("date")?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val med = Store.meds(c).find { it.id == medId }

        when (i.action) {
            Alarms.ACTION_FIRE, Alarms.ACTION_SNOOZE_FIRE -> {
                // 정기 알람이면 다음 날 같은 시간 다시 예약
                if (i.action == Alarms.ACTION_FIRE && med != null && time in med.times) {
                    Alarms.schedule(c, med.id, time)
                }
                // 앱에서 이미 체크했으면 알림 안 띄움
                if (med != null && Store.takenAt(c, date, medId, time) == null) {
                    Notifier.show(c, med, time, date)
                }
            }
            Alarms.ACTION_TAKE -> {
                Store.setTaken(c, date, medId, time, true)
                Notifier.cancel(c, medId, time)
            }
            Alarms.ACTION_SNOOZE -> {
                Notifier.cancel(c, medId, time)
                Alarms.snooze(c, medId, time, date)
            }
        }
    }
}

/** 폰 재부팅·앱 업데이트·시간 변경 뒤 알람 다시 걸기 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = Alarms.rescheduleAll(c)
}

object Notifier {
    private const val CH = "dose"

    fun ensure(c: Context) {
        val ch = NotificationChannel(CH, "복용 알림", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
        }
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    @SuppressLint("MissingPermission")
    fun show(c: Context, med: Med, time: String, date: LocalDate) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        ensure(c)
        val open = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = listOf(time, med.memo).filter { it.isNotBlank() }.joinToString(" · ")
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_pill)
            .setContentTitle("💊 ${med.name} 먹을 시간이에요")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "먹었어요", Alarms.intent(c, Alarms.ACTION_TAKE, med.id, time, date))
            .addAction(0, "${Alarms.SNOOZE_MIN}분 뒤", Alarms.intent(c, Alarms.ACTION_SNOOZE, med.id, time, date))
            .build()
        NotificationManagerCompat.from(c).notify(Alarms.code(med.id, time), n)
    }

    fun cancel(c: Context, medId: Int, time: String) =
        NotificationManagerCompat.from(c).cancel(Alarms.code(medId, time))
}
