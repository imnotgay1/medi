package com.sanghwan.medi

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 홈 화면 위젯: 오늘 먹을 약 + 눌러서 바로 체크 */
class MediWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        updateAll(c)
    }

    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            ACTION_TOGGLE -> handleToggle(c, i)
            ACTION_REFRESH -> updateAll(c)
            else -> super.onReceive(c, i)
        }
    }

    internal data class Dose(val med: Med, val time: String, val takenAt: Long?)

    companion object {
        const val ACTION_TOGGLE = "com.sanghwan.medi.WIDGET_TOGGLE"
        private const val ACTION_REFRESH = "com.sanghwan.medi.WIDGET_REFRESH"

        /** 위젯에서 약을 눌렀을 때 체크/취소 */
        fun handleToggle(c: Context, i: Intent) {
            val medId = i.getIntExtra("medId", -1)
            val time = i.getStringExtra("time")
            val date = i.getStringExtra("date")
            // 날짜가 바뀐 뒤 옛 화면을 누른 경우엔 새로고침만
            if (time != null && date == LocalDate.now().toString()) {
                val today = LocalDate.now()
                val med = Store.meds(c).find { it.id == medId } ?: return updateAll(c)
                val taken = Store.takenAt(c, today, med, time) != null
                Store.setTaken(c, today, med, time, !taken) // 위젯 갱신은 Store가 함
                if (!taken) {
                    if (med.once) Notifier.cancelMed(c, med) else Notifier.cancel(c, medId, time)
                }
            } else {
                updateAll(c)
            }
        }

        internal fun todayDoses(c: Context): List<Dose> {
            val today = LocalDate.now()
            return Store.meds(c)
                .flatMap { m -> Store.doseTimes(m).map { Dose(m, it, Store.takenAt(c, today, m, it)) } }
                .sortedBy { it.time }
        }
        private const val MAX_ROWS = 4

        private val ROW = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3)
        private val TIME = intArrayOf(R.id.time0, R.id.time1, R.id.time2, R.id.time3)
        private val NAME = intArrayOf(R.id.name0, R.id.name1, R.id.name2, R.id.name3)
        private val CHECK = intArrayOf(R.id.check0, R.id.check1, R.id.check2, R.id.check3)

        internal fun fmt(ms: Long): String =
            java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
                .format(DateTimeFormatter.ofPattern("HH:mm"))

        fun updateAll(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            val big = mgr.getAppWidgetIds(ComponentName(c, MediWidget::class.java))
            val small = mgr.getAppWidgetIds(ComponentName(c, SmallWidget::class.java))
            if (big.isEmpty() && small.isEmpty()) return
            if (big.isNotEmpty()) {
                val views = build(c)
                big.forEach { mgr.updateAppWidget(it, views) }
            }
            if (small.isNotEmpty()) {
                val views = SmallWidget.build(c)
                small.forEach { mgr.updateAppWidget(it, views) }
            }
            scheduleMidnight(c)
        }

        /** 자정이 지나면 새 날짜로 바꾸기 */
        private fun scheduleMidnight(c: Context) {
            val at = LocalDate.now().plusDays(1).atStartOfDay().plusSeconds(30)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pi = PendingIntent.getBroadcast(
                c, 999,
                Intent(c, MediWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            c.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at, pi)
        }

        private fun build(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget)
            val today = LocalDate.now()
            val now = LocalTime.now()
            val color = { id: Int -> ContextCompat.getColor(c, id) }

            val all = Store.meds(c)
                .flatMap { m -> Store.doseTimes(m).map { Dose(m, it, Store.takenAt(c, today, m, it)) } }
                .sortedBy { it.time }
            val done = all.count { it.takenAt != null }

            v.setTextViewText(
                R.id.w_title,
                when {
                    all.isEmpty() -> "💊 약 알림"
                    done == all.size -> "💊 오늘 다 먹었어요"
                    else -> "💊 오늘 $done/${all.size}"
                }
            )
            v.setTextViewText(R.id.w_date, today.format(DateTimeFormatter.ofPattern("M/d (E)", Locale.KOREAN)))
            v.setViewVisibility(R.id.w_empty, if (all.isEmpty()) View.VISIBLE else View.GONE)

            val open = PendingIntent.getActivity(
                c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.w_header, open)
            v.setOnClickPendingIntent(R.id.w_empty, open)

            // 안 먹은 것 먼저 골라서, 시간순으로 표시
            val show = (all.filter { it.takenAt == null } + all.filter { it.takenAt != null })
                .take(MAX_ROWS)
                .sortedBy { it.time }

            for (idx in 0 until MAX_ROWS) {
                val d = show.getOrNull(idx)
                if (d == null) {
                    v.setViewVisibility(ROW[idx], View.GONE)
                    continue
                }
                val taken = d.takenAt != null
                val late = !taken && LocalTime.parse(d.time).isBefore(now)

                v.setViewVisibility(ROW[idx], View.VISIBLE)
                v.setTextViewText(TIME[idx], d.takenAt?.let { fmt(it) } ?: d.time)
                v.setTextViewText(NAME[idx], d.med.name)
                v.setTextViewText(CHECK[idx], if (taken) "✓" else "○")
                v.setTextColor(TIME[idx], color(if (late) R.color.w_late else if (taken) R.color.w_sub else R.color.w_text))
                v.setTextColor(NAME[idx], color(if (late) R.color.w_late else if (taken) R.color.w_sub else R.color.w_text))
                v.setTextColor(CHECK[idx], color(if (taken) R.color.w_accent else if (late) R.color.w_late else R.color.w_sub))

                val toggle = Intent(c, MediWidget::class.java)
                    .setAction(ACTION_TOGGLE)
                    .putExtra("medId", d.med.id)
                    .putExtra("time", d.time)
                    .putExtra("date", today.toString())
                v.setOnClickPendingIntent(
                    ROW[idx],
                    PendingIntent.getBroadcast(
                        c, 100 + idx, toggle,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            val more = all.size - show.size
            v.setViewVisibility(R.id.w_more, if (more > 0) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.w_more, "+${more}개 더")
            return v
        }
    }
}
