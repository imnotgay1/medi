package com.sanghwan.medi

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 정해진 시간에 알림이 울리도록 예약 */
object Alarms {
    const val ACTION_FIRE = "com.sanghwan.medi.FIRE"
    const val ACTION_SNOOZE_FIRE = "com.sanghwan.medi.SNOOZE_FIRE"
    const val ACTION_TAKE = "com.sanghwan.medi.TAKE"
    const val ACTION_SNOOZE = "com.sanghwan.medi.SNOOZE"
    const val SNOOZE_MIN = 10

    /** 약id + 시간으로 고유 번호 (예: 약3, 08:30 -> 30830) */
    fun code(medId: Int, time: String) = medId * 10000 + time.replace(":", "").toInt()

    fun intent(c: Context, action: String, medId: Int, time: String, date: LocalDate? = null): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java)
            .setAction(action)
            .putExtra("medId", medId)
            .putExtra("time", time)
        if (date != null) i.putExtra("date", date.toString())
        return PendingIntent.getBroadcast(
            c, code(medId, time), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    fun canExact(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || am(c).canScheduleExactAlarms()

    private fun setAt(c: Context, at: Long, pi: PendingIntent) {
        if (canExact(c)) am(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun nextTrigger(time: String): Long {
        var dt = LocalDate.now().atTime(LocalTime.parse(time))
        if (!dt.isAfter(LocalDateTime.now())) dt = dt.plusDays(1)
        return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun schedule(c: Context, medId: Int, time: String) =
        setAt(c, nextTrigger(time), intent(c, ACTION_FIRE, medId, time))

    fun snooze(c: Context, medId: Int, time: String, date: LocalDate) =
        setAt(
            c, System.currentTimeMillis() + SNOOZE_MIN * 60_000L,
            intent(c, ACTION_SNOOZE_FIRE, medId, time, date)
        )

    fun cancelMed(c: Context, med: Med) {
        med.times.forEach {
            am(c).cancel(intent(c, ACTION_FIRE, med.id, it))
            am(c).cancel(intent(c, ACTION_SNOOZE_FIRE, med.id, it))
            Notifier.cancel(c, med.id, it)
        }
    }

    fun rescheduleAll(c: Context) {
        Store.meds(c).forEach { m -> m.times.forEach { schedule(c, m.id, it) } }
    }
}
