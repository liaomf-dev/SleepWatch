package com.example.sleepwatch

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 共用的畫面元件與顏色 */
object Ui {
    val BG = 0xFF0E1325.toInt()
    val CARD = 0xFF1A2140.toInt()
    val ACCENT = 0xFF8C9EFF.toInt()
    val ACCENT_DARK = 0xFF3D4B9E.toInt()
    val DANGER = 0xFF8E3B46.toInt()
    val TEXT = 0xFFF1F3FA.toInt()
    val DIM = 0xFF9AA3C7.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun text(c: Context, s: CharSequence, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        t.setLineSpacing(0f, 1.2f)
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        return t
    }

    fun rounded(color: Int, radiusPx: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radiusPx.toFloat()
        return g
    }

    fun button(c: Context, s: String, bg: Int): Button {
        val b = Button(c)
        b.text = s
        b.isAllCaps = false
        b.textSize = 16f
        b.setTextColor(Color.WHITE)
        b.background = rounded(bg, dp(c, 12))
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10))
        return b
    }

    fun column(c: Context, padDp: Int): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        val p = dp(c, padDp)
        l.setPadding(p, p, p, p)
        return l
    }

    fun card(c: Context): LinearLayout {
        val l = column(c, 16)
        l.background = rounded(CARD, dp(c, 16))
        return l
    }

    fun full(c: Context, topDp: Int, heightPx: Int = LinearLayout.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx)
        lp.topMargin = dp(c, topDp)
        return lp
    }

    fun durationText(ms: Long): String {
        val min = ms / 60_000
        return if (min >= 60) "${min / 60} 小時 ${min % 60} 分" else "$min 分鐘"
    }

    fun dateRange(s: Session): String {
        val d = SimpleDateFormat("M/d（E） HH:mm", Locale.TAIWAN).format(Date(s.start))
        val e = SimpleDateFormat("HH:mm", Locale.TAIWAN).format(Date(s.end))
        return "$d → $e"
    }

    fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.TAIWAN).format(Date(ms))

    fun scoreLabel(score: Int): String = when {
        score >= 80 -> "良好"
        score >= 60 -> "普通"
        else -> "需要留意"
    }
}
