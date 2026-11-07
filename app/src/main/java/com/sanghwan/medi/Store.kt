package com.sanghwan.medi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** 약 하나: 이름, 메모, 복용 시간들("HH:mm"), 등록일 */
data class Med(
    val id: Int,
    val name: String,
    val memo: String,
    val times: List<String>,
    val since: String,
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
            )
        }
        p(c).edit().putString("meds", arr.toString()).apply()
        MediWidget.updateAll(c)
    }

    fun save(c: Context, id: Int?, name: String, memo: String, times: List<String>): Med {
        val list = meds(c).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        val med = if (idx >= 0) {
            list[idx].copy(name = name, memo = memo, times = times.sorted())
        } else {
            val n = p(c).getInt("nextId", 1)
            p(c).edit().putInt("nextId", n + 1).apply()
            Med(n, name, memo, times.sorted(), LocalDate.now().toString())
        }
        if (idx >= 0) list[idx] = med else list.add(med)
        write(c, list)
        return med
    }

    fun delete(c: Context, id: Int) = write(c, meds(c).filter { it.id != id })

    // ---- 복용 기록: "날짜|약id|시간" -> 먹은 시각(ms)
    private fun logs(c: Context) = JSONObject(p(c).getString("logs", "{}") ?: "{}")
    private fun key(d: LocalDate, id: Int, t: String) = "$d|$id|$t"

    fun takenAt(c: Context, d: LocalDate, id: Int, t: String): Long? {
        val v = logs(c).optLong(key(d, id, t), 0L)
        return if (v > 0) v else null
    }

    fun setTaken(c: Context, d: LocalDate, id: Int, t: String, taken: Boolean) {
        val l = logs(c)
        if (taken) l.put(key(d, id, t), System.currentTimeMillis()) else l.remove(key(d, id, t))
        // 90일 지난 기록은 정리
        val cutoff = LocalDate.now().minusDays(90).toString()
        val old = mutableListOf<String>()
        l.keys().forEach { k -> if (k.substringBefore("|") < cutoff) old.add(k) }
        old.forEach { l.remove(it) }
        p(c).edit().putString("logs", l.toString()).apply()
        MediWidget.updateAll(c)
    }
}
