package com.example.sleepwatch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一個聲音事件：開始時間（相對於開始記錄）、有聲音的秒數、類型、最大音量、錄音檔名 */
class SoundEvent(
    val offsetMs: Long,
    val durationSec: Float,
    val type: String,
    val peak: Int,
    val file: String
)

/** 一晚的紀錄，存在 files/sessions/<開始時間>/meta.json */
class Session(val dir: File) {
    val id: String get() = dir.name
    var start = 0L
    var end = 0L
    var delayMin = 0
    val minuteAvg = ArrayList<Int>()
    val minuteMax = ArrayList<Int>()
    val events = ArrayList<SoundEvent>()

    val hours: Double get() = (end - start).coerceAtLeast(0L) / 3_600_000.0

    fun count(type: String): Int = events.count { it.type == type }

    fun seconds(type: String): Double =
        events.filter { it.type == type }.sumOf { it.durationSec.toDouble() }

    private fun monitoredMinutes(): Int = (minuteAvg.size - delayMin).coerceAtLeast(1)

    /** 沒有任何聲音事件的分鐘比例 */
    fun quietPercent(): Int {
        val noisy = HashSet<Long>()
        for (e in events) {
            val first = e.offsetMs / 60_000
            val last = (e.offsetMs + (e.durationSec * 1000).toLong()) / 60_000
            for (m in first..last) noisy.add(m)
        }
        val total = monitoredMinutes()
        return ((total - noisy.size).coerceAtLeast(0) * 100 / total)
    }

    fun snorePercent(): Double = seconds(TYPE_SNORE) / 60.0 / monitoredMinutes() * 100.0

    /** 簡易睡眠分數（只依聲音與時長估算，僅供參考） */
    fun score(): Int {
        var s = 100.0
        s -= minOf(40.0, snorePercent() * 2.0)
        s -= minOf(20.0, count(TYPE_TALK) * 3.0)
        s -= minOf(15.0, count(TYPE_OTHER) * 0.5)
        if (hours < 6.0) s -= minOf(25.0, (6.0 - hours) * 8.0)
        return s.toInt().coerceIn(0, 100)
    }

    fun save() {
        val o = JSONObject()
        o.put("start", start)
        o.put("end", end)
        o.put("delayMin", delayMin)
        val avg = JSONArray()
        for (v in minuteAvg) avg.put(v)
        val mx = JSONArray()
        for (v in minuteMax) mx.put(v)
        o.put("avg", avg)
        o.put("max", mx)
        val arr = JSONArray()
        for (e in events) {
            val j = JSONObject()
            j.put("t", e.offsetMs)
            j.put("d", e.durationSec.toDouble())
            j.put("type", e.type)
            j.put("peak", e.peak)
            j.put("file", e.file)
            arr.put(j)
        }
        o.put("events", arr)
        val tmp = File(dir, "meta.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, "meta.json"))
    }

    companion object {
        const val TYPE_SNORE = "snore"
        const val TYPE_TALK = "talk"
        const val TYPE_OTHER = "other"

        fun root(ctx: Context): File {
            val f = File(ctx.filesDir, "sessions")
            f.mkdirs()
            return f
        }

        fun load(dir: File): Session? {
            return try {
                val o = JSONObject(File(dir, "meta.json").readText())
                val s = Session(dir)
                s.start = o.getLong("start")
                s.end = o.optLong("end", 0L)
                s.delayMin = o.optInt("delayMin", 0)
                val avg = o.getJSONArray("avg")
                for (i in 0 until avg.length()) s.minuteAvg.add(avg.getInt(i))
                val mx = o.getJSONArray("max")
                for (i in 0 until mx.length()) s.minuteMax.add(mx.getInt(i))
                val arr = o.getJSONArray("events")
                for (i in 0 until arr.length()) {
                    val j = arr.getJSONObject(i)
                    s.events.add(
                        SoundEvent(
                            j.getLong("t"),
                            j.getDouble("d").toFloat(),
                            j.getString("type"),
                            j.getInt("peak"),
                            j.optString("file", "")
                        )
                    )
                }
                if (s.end <= s.start) s.end = s.start + s.minuteAvg.size * 60_000L
                s
            } catch (e: Exception) {
                null
            }
        }

        fun all(ctx: Context): List<Session> {
            val dirs = root(ctx).listFiles() ?: return emptyList()
            return dirs.mapNotNull { load(it) }.sortedByDescending { it.start }
        }

        fun typeName(t: String): String = when (t) {
            TYPE_SNORE -> "打呼"
            TYPE_TALK -> "說話"
            else -> "其他聲響"
        }

        fun typeColor(t: String): Int = when (t) {
            TYPE_SNORE -> 0xFFF4A261.toInt()
            TYPE_TALK -> 0xFF64B5F6.toInt()
            else -> 0xFF9E9E9E.toInt()
        }
    }
}
