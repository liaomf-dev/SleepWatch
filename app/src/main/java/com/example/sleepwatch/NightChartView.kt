package com.example.sleepwatch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

/** 整晚音量長條圖：每一根是一分鐘，下方色塊標示打呼／說話／其他聲響發生的時間 */
class NightChartView(context: Context, private val s: Session) : View(context) {

    private val d = resources.displayMetrics.density
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val delayPaint = Paint()
    private val gridPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        avgPaint.color = 0xFF5C6BC0.toInt()
        maxPaint.color = 0x558C9EFF
        delayPaint.color = 0x55000000
        gridPaint.color = 0x22FFFFFF
        gridPaint.strokeWidth = d
        textPaint.color = Ui.DIM
        textPaint.textSize = 11f * d
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = s.minuteAvg.size
        val w = width.toFloat()
        val h = height.toFloat()
        val labelH = 16f * d
        val markH = 12f * d
        val chartH = h - labelH - markH - 8f * d

        if (n == 0) {
            canvas.drawText("記錄時間太短，沒有圖表資料", 0f, h / 2f, textPaint)
            return
        }

        val lo = (s.minuteAvg.minOrNull() ?: 0) - 3
        val hi = max(s.minuteMax.maxOrNull() ?: 0, lo + 15)
        val bw = w / n
        val barW = max(bw - 0.5f * d, 1f)

        fun y(v: Int): Float = chartH - (v - lo).toFloat() / (hi - lo).toFloat() * chartH

        for (i in 0 until n) {
            val x = i * bw
            val mx = if (i < s.minuteMax.size) s.minuteMax[i] else s.minuteAvg[i]
            canvas.drawRect(x, y(mx), x + barW, chartH, maxPaint)
            canvas.drawRect(x, y(s.minuteAvg[i]), x + barW, chartH, avgPaint)
        }

        // 延遲開始偵測的時段，用較暗的顏色蓋住
        if (s.delayMin > 0) {
            canvas.drawRect(0f, 0f, min(s.delayMin.toFloat() / n * w, w), chartH, delayPaint)
        }

        // 事件標記
        val my = chartH + 4f * d
        for (e in s.events) {
            val x = e.offsetMs / 60_000f / n * w
            val ew = max(e.durationSec / 60f / n * w, 2f * d)
            markPaint.color = Session.typeColor(e.type)
            canvas.drawRect(x, my, min(x + ew, w), my + markH, markPaint)
        }

        // 整點時間刻度
        val cal = Calendar.getInstance()
        cal.timeInMillis = s.start
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.HOUR_OF_DAY, 1)
        val endMs = s.start + n * 60_000L
        while (cal.timeInMillis < endMs) {
            val x = (cal.timeInMillis - s.start) / 60_000f / n * w
            canvas.drawLine(x, 0f, x, chartH, gridPaint)
            val label = cal.get(Calendar.HOUR_OF_DAY).toString()
            canvas.drawText(label, x - textPaint.measureText(label) / 2f, h - 2f * d, textPaint)
            cal.add(Calendar.HOUR_OF_DAY, 1)
        }
    }
}
