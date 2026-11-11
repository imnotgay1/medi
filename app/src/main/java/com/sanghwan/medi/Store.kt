package com.sanghwan.medi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 약 하나: 이름, 메모, 알림 시간들("HH:mm"), 등록일
 * once = true 이면 알림은 여러 번 와도 하루 1번만 먹으면 끝
 */
data class Med(
    val id: Int,
    val name: String,
    val memo: String,
    val times: List<String>,
    val since: String,
    val once: Boolean = true,
)

/** 폰 안에만 저장 (SharedPreferences) */
object Store {
    private fun p(c: Context) = c.getSharedPreferences("medi", Context.MODE_PRIVATE)

    fun meds(c: Context): List<Med> {
        val arr = JSONArray(p(c).getString("meds", "[]") ?: "[]")
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val t = o.getJSONArray("times")
            Med(
                id = o.getInt("id"),
                name = o.getString("name"),
                memo = o.optString("memo", ""),
                times = List(t.length()) { t.getString(it) }.sorted(),
                since = o.optString("since", LocalDate.now().toString()),
                once = o.optBoolean("once", true),
            )
        }
    }

    private fun write(c: Context, list: List<Med>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("memo", m.memo)
                    .put("times", JSONArray(m.times))
                    .put("since", m.since)
                    .put("once", m.once)
            )
        }
        p(c).edit().putString("meds", arr.toString()).apply()
        MediWidget.updateAll(c)
    }

    fun save(c: Context, id: Int?, name: String, memo: String, times: List<String>, once: Boolean): Med {
        val list = meds(c).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        val med = if (idx >= 0) {
            list[idx].copy(name = name, memo = memo, times = times.sorted(), once = once)
        } else {
            val n = p(c).getInt("nextId", 1)
            p(c).edit().putInt("nextId", n + 1).apply()
            Med(n, name, memo, times.sorted(), LocalDate.now().toString(), once)
        }
        if (idx >= 0) list[idx] = med else list.add(med)
        write(c, list)
        return med
    }

    fun delete(c: Context, id: Int) = write(c, meds(c).filter { it.id != id })

    /** 하루에 체크할 칸: 1회 약이면 첫 알림 시간 하나, 아니면 알림 시간마다 */
    fun doseTimes(m: Med): List<String> = if (m.once) m.times.take(1) else m.times

    // ---- 복용 기록: "날짜|약id|칸" -> 먹은 시각(ms). 1회 약은 칸이 "DAY" 하나
    private fun logs(c: Context) = JSONObject(p(c).getString("logs", "{}") ?: "{}")
    private fun key(d: LocalDate, m: Med, t: String) = "$d|${m.id}|${if (m.once) "DAY" else t}"

    fun takenAt(c: Context, d: LocalDate, m: Med, t: String): Long? {
        val l = logs(c)
        val v = l.optLong(key(d, m, t), 0L)
        if (v > 0) return v
        if (!m.once) return null
        // 예전 방식(알림 시간별)으로 저장된 기록도 인정: 그날 가장 먼저 먹은 시간
        val prefix = "$d|${m.id}|"
        var first = 0L
        l.keys().forEach { k ->
            if (k.startsWith(prefix)) {
                val x = l.optLong(k, 0L)
                if (x > 0 && (first == 0L || x < first)) first = x
            }
        }
        return if (first > 0) first else null
    }

    fun setTaken(
        c: Context, d: LocalDate, m: Med, t: String, taken: Boolean,
        at: Long = System.currentTimeMillis(),
    ) {
        val l = logs(c)
        if (m.once) {
            // 그날 이 약 기록을 한 칸으로 정리
            val prefix = "$d|${m.id}|"
            val old = mutableListOf<String>()
            l.keys().forEach { k -> if (k.startsWith(prefix)) old.add(k) }
            old.forEach { l.remove(it) }
        }
        if (taken) l.put(key(d, m, t), at) else l.remove(key(d, m, t))
        // 90일 지난 기록은 정리
        val cutoff = LocalDate.now().minusDays(90).toString()
        val old = mutableListOf<String>()
        l.keys().forEach { k -> if (k.substringBefore("|") < cutoff) old.add(k) }
        old.forEach { l.remove(it) }
        p(c).edit().putString("logs", l.toString()).apply()
        MediWidget.updateAll(c)
    }
}
