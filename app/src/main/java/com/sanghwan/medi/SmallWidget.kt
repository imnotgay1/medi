package com.sanghwan.medi

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalTime

/** 1칸 위젯: 다음에 먹을 약 하나만 표시, 누르면 체크 */
class SmallWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        MediWidget.updateAll(c)
    }

    override fun onReceive(c: Context, i: Intent) {
        if (i.action == MediWidget.ACTION_TOGGLE) MediWidget.handleToggle(c, i)
        else super.onReceive(c, i)
    }

    companion object {
        fun build(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_small)
            val color = { id: Int -> ContextCompat.getColor(c, id) }
            val all = MediWidget.todayDoses(c)
            val done = all.count { it.takenAt != null }
            val next = all.firstOrNull { it.takenAt == null } // 시간순 첫 번째 안 먹은 약

            val open = PendingIntent.getActivity(
                c, 1, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )

            v.setTextViewText(R.id.s_count, if (all.isEmpty()) "💊" else "💊 $done/${all.size}")

            if (next == null) {
                // 약이 없거나 다 먹음
                v.setTextViewText(R.id.s_time, if (all.isEmpty()) "+" else "✓")
                v.setTextColor(R.id.s_time, color(R.color.w_accent))
                v.setTextViewText(R.id.s_name, if (all.isEmpty()) "약 추가" else "다 먹음")
                v.setTextColor(R.id.s_name, color(R.color.w_sub))
                v.setOnClickPendingIntent(R.id.s_root, open)
                return v
            }

            val late = LocalTime.parse(next.time).isBefore(LocalTime.now())
            v.setTextViewText(R.id.s_time, next.time)
            v.setTextColor(R.id.s_time, color(if (late) R.color.w_late else R.color.w_text))
            v.setTextViewText(R.id.s_name, next.med.name)
            v.setTextColor(R.id.s_name, color(if (late) R.color.w_late else R.color.w_sub))

            val toggle = Intent(c, SmallWidget::class.java)
                .setAction(MediWidget.ACTION_TOGGLE)
                .putExtra("medId", next.med.id)
                .putExtra("time", next.time)
                .putExtra("date", LocalDate.now().toString())
            v.setOnClickPendingIntent(
                R.id.s_root,
                PendingIntent.getBroadcast(
                    c, 200, toggle,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            return v
        }
    }
}
