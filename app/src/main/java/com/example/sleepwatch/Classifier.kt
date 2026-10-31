package com.example.sleepwatch

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** 使用者設定 */
object Prefs {
    private fun p(c: Context): SharedPreferences = c.getSharedPreferences("sleep_watch", Context.MODE_PRIVATE)

    /** 靈敏度：0 低、1 中、2 高 */
    fun sensitivity(c: Context): Int = p(c).getInt("sensitivity", 1)
    fun setSensitivity(c: Context, v: Int) = p(c).edit().putInt("sensitivity", v).apply()
    fun sensitivityName(v: Int): String = when (v) { 0 -> "低（靠近馬路建議）"; 2 -> "高"; else -> "中" }

    /** 聲音要高於環境噪音多少 dB 才記錄 */
    fun triggerDb(c: Context): Double = when (sensitivity(c)) { 0 -> 18.0; 2 -> 8.0; else -> 12.0 }

    /** 錄音保留天數，0 = 不自動刪除 */
    fun retentionDays(c: Context): Int = p(c).getInt("retention", 14)
    fun setRetentionDays(c: Context, v: Int) = p(c).edit().putInt("retention", v).apply()
    fun retentionName(v: Int): String = if (v <= 0) "不自動刪除" else "$v 天"

    /** 環境噪音（車聲等）是否也存錄音 */
    fun saveNoise(c: Context): Boolean = p(c).getBoolean("save_noise", false)
    fun setSaveNoise(c: Context, v: Boolean) = p(c).edit().putBoolean("save_noise", v).apply()

    fun learned(c: Context): String = p(c).getString("learned", "[]") ?: "[]"
    fun setLearned(c: Context, v: String) = p(c).edit().putString("learned", v).apply()
}

/**
 * 分析一個聲音事件，判斷是打呼、說話、環境噪音或其他。
 *
 * 判斷依據：
 * - 低頻比例、過零率：打呼的能量集中在低頻
 * - 人聲音高：說話時聲帶振動，有 80–400 Hz 且會變化的音高；車聲多半沒有音高，引擎聲音高則很固定
 * - 音節節奏：說話時音量每秒起伏 2–8 次；車子經過是慢慢變大再慢慢變小
 */
class EventAnalyzer(private val rate: Int) {

    /** 每 0.1 秒一格的資料 */
    private class Frame(val loud: Boolean, val zcr: Double, val low: Double, val f0: Double, val env: DoubleArray)

    private val frames = ArrayList<Frame>()
    private var loudFrames = 0
    private var bursts = 0
    private var prevLoud = false

    val loudCount: Int get() = loudFrames

    fun add(frame: ShortArray, loud: Boolean, zcr: Double, lowRatio: Double) {
        // 每 20 毫秒的音量（dB），用來計算音節節奏
        val sub = rate / 50
        val env = DoubleArray(frame.size / sub)
        for (k in env.indices) {
            var s = 0.0
            for (j in k * sub until (k + 1) * sub) {
                val v = frame[j].toDouble()
                s += v * v
            }
            env[k] = 20.0 * log10(max(sqrt(s / sub), 1.0))
        }
        val f0 = if (loud) pitchOf(frame, rate) else 0.0
        frames.add(Frame(loud, zcr, lowRatio, f0, env))
        if (loud) {
            loudFrames++
            if (!prevLoud) bursts++
        }
        prevLoud = loud
    }

    /** 特徵值：[過零率/1000, 低頻比例, 有人聲音高的比例, 音高變化, 音節速率/4, 長度] */
    fun features(): FloatArray = featuresOf(0, frames.size)

    private fun featuresOf(from: Int, to: Int): FloatArray {
        var n = 0
        var zcrSum = 0.0
        var lowSum = 0.0
        var voiced = 0
        val pitches = ArrayList<Double>()
        val env = ArrayList<Double>()
        for (i in from until to) {
            val fr = frames[i]
            for (v in fr.env) env.add(v)
            if (!fr.loud) continue
            n++
            zcrSum += fr.zcr
            lowSum += fr.low
            if (fr.f0 > 0) {
                voiced++
                pitches.add(fr.f0)
            }
        }
        val d = max(n, 1)
        val sec = n / 10.0
        return floatArrayOf(
            (zcrSum / d / 1000.0).toFloat(),
            (lowSum / d).toFloat(),
            (voiced.toDouble() / d).toFloat(),
            pitchVariation(pitches).toFloat(),
            (syllableRate(env, sec) / 4.0).toFloat(),
            (ln(1.0 + sec) / 2.0).toFloat()
        )
    }

    private fun looksLikeTalk(f: FloatArray): Boolean =
        f[2] >= 0.25 && f[4] * 4.0 >= 1.5 && f[3] >= 0.08

    /** 長的事件（例如說話時剛好有車經過）分成 2 秒一段檢查，只要有一段像說話就算說話 */
    private fun talkInside(): Boolean {
        if (frames.size <= 25) return false
        var i = 0
        while (i + 20 <= frames.size) {
            var loud = 0
            for (k in i until i + 20) if (frames[k].loud) loud++
            if (loud >= 8 && looksLikeTalk(featuresOf(i, i + 20))) return true
            i += 5
        }
        return false
    }

    fun classify(f: FloatArray): String {
        val zcrAvg = f[0] * 1000.0
        val lowAvg = f[1].toDouble()
        val sylRate = f[4] * 4.0
        return when {
            loudFrames < 6 && bursts <= 1 -> Session.TYPE_OTHER
            lowAvg > 0.55 && zcrAvg < 1200.0 && sylRate < 1.5 && (bursts >= 2 || loudFrames <= 30) -> Session.TYPE_SNORE
            looksLikeTalk(f) || talkInside() -> Session.TYPE_TALK
            loudFrames >= 20 -> Session.TYPE_NOISE
            else -> Session.TYPE_OTHER
        }
    }

    /** 音高的變化程度：(第 90 百分位 − 第 10 百分位) ÷ 中位數 */
    private fun pitchVariation(pitches: List<Double>): Double {
        if (pitches.size < 3) return 0.0
        val s = pitches.sorted()
        val lo = s[(s.size * 0.1).toInt()]
        val hi = s[minOf(s.size - 1, (s.size * 0.9).toInt())]
        val med = s[s.size / 2]
        return (hi - lo) / med
    }

    /** 每秒有幾個明顯的音量高峰（像音節一樣） */
    private fun syllableRate(e: List<Double>, sec: Double): Double {
        var peaks = 0
        var i = 4
        while (i < e.size - 4) {
            var isMax = true
            for (j in i - 4..i + 4) if (e[j] > e[i]) { isMax = false; break }
            if (isMax) {
                var lo = e[i]
                for (j in max(0, i - 10)..minOf(e.size - 1, i + 10)) if (e[j] < lo) lo = e[j]
                if (e[i] - lo >= 6.0) {
                    peaks++
                    i += 4
                }
            }
            i++
        }
        return peaks / max(sec, 0.5)
    }

    companion object {
        /** 用自相關找音高（50–400 Hz），沒有明顯音高時回傳 0 */
        fun pitchOf(frame: ShortArray, rate: Int): Double {
            val win = minOf(800, frame.size / 2)
            val start = (frame.size - win) / 2
            val minLag = rate / 400
            val maxLag = minOf(rate / 50, frame.size - start - win)
            var mean = 0.0
            for (k in start until start + win + maxLag) mean += frame[k]
            mean /= (win + maxLag)
            val r = DoubleArray(maxLag + 2)
            for (lag in minLag - 1..maxLag + 1) {
                if (lag + start + win > frame.size) break
                var xy = 0.0
                var xx = 0.0
                var yy = 0.0
                for (k in start until start + win) {
                    val a = frame[k] - mean
                    val b = frame[k + lag] - mean
                    xy += a * b
                    xx += a * a
                    yy += b * b
                }
                r[lag] = if (xx > 0 && yy > 0) xy / sqrt(xx * yy) else 0.0
            }
            var best = -1
            for (lag in minLag..maxLag) {
                if (lag + 1 >= r.size) break
                if (r[lag] > r[lag - 1] && r[lag] >= r[lag + 1] && r[lag] > 0.45) {
                    if (best < 0 || r[lag] > r[best]) best = lag
                }
            }
            if (best < 0) return 0.0
            // 避免抓到兩倍週期（音高被誤判低一個八度）
            val half = best / 2
            if (half >= minLag && half + 1 < r.size &&
                r[half] > r[half - 1] && r[half] >= r[half + 1] && r[half] > r[best] * 0.85
            ) best = half
            return rate.toDouble() / best
        }
    }
}

/** 使用者修正過的分類會被記住，之後遇到很像的聲音就套用 */
object Learner {
    private const val MAX = 300
    private const val THRESHOLD = 0.3

    fun add(c: Context, f: FloatArray?, type: String) {
        if (f == null || f.isEmpty()) return
        val arr = try { JSONArray(Prefs.learned(c)) } catch (e: Exception) { JSONArray() }
        val o = JSONObject()
        val fa = JSONArray()
        for (v in f) fa.put(v.toDouble())
        o.put("f", fa)
        o.put("type", type)
        arr.put(o)
        val out = JSONArray()
        val from = max(0, arr.length() - MAX)
        for (i in from until arr.length()) out.put(arr.getJSONObject(i))
        Prefs.setLearned(c, out.toString())
    }

    /** 找最像的已修正例子；夠像就回傳它的分類，否則 null */
    fun match(examples: List<Pair<FloatArray, String>>, f: FloatArray): String? {
        var best: String? = null
        var bestD = Double.MAX_VALUE
        for ((ef, type) in examples) {
            if (ef.size != f.size) continue
            var d = 0.0
            for (i in f.indices) {
                val x = (ef[i] - f[i]).toDouble()
                d += x * x
            }
            if (d < bestD) {
                bestD = d
                best = type
            }
        }
        return if (sqrt(bestD) < THRESHOLD) best else null
    }

    fun load(c: Context): List<Pair<FloatArray, String>> {
        val list = ArrayList<Pair<FloatArray, String>>()
        try {
            val arr = JSONArray(Prefs.learned(c))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val fa = o.getJSONArray("f")
                val f = FloatArray(fa.length())
                for (k in 0 until fa.length()) f[k] = fa.getDouble(k).toFloat()
                list.add(Pair(f, o.getString("type")))
            }
        } catch (e: Exception) {
        }
        return list
    }

    fun count(c: Context): Int = load(c).size
    fun clear(c: Context) = Prefs.setLearned(c, "[]")
}
