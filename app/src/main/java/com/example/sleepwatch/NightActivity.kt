package com.example.sleepwatch

import android.app.Activity
import android.app.AlertDialog
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/** 單晚的詳細結果：分數、摘要、整晚音量圖、可播放的事件列表 */
class NightActivity : Activity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private lateinit var session: Session
    private lateinit var eventsBox: LinearLayout
    private var player: MediaPlayer? = null
    private var playingButton: Button? = null
    private var filter: String? = null
    private val filterButtons = ArrayList<Pair<String?, Button>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val id = intent.getStringExtra(EXTRA_ID)
        val loaded = if (id != null) Session.load(File(Session.root(this), id)) else null
        if (loaded == null) {
            Toast.makeText(this, "找不到這晚的紀錄", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        session = loaded
        val s = loaded

        val root = Ui.column(this, 20)
        root.addView(Ui.text(this, Ui.dateRange(s), 22f, Ui.TEXT, bold = true))

        // 分數
        val scoreCard = Ui.card(this)
        val score = s.score()
        scoreCard.addView(Ui.text(this, "睡眠聲音分數", 14f, Ui.DIM))
        scoreCard.addView(Ui.text(this, "$score  ${Ui.scoreLabel(score)}", 40f, Ui.TEXT, bold = true))
        val summary = StringBuilder()
        summary.append("記錄時間：").append(Ui.durationText(s.end - s.start)).append('\n')
        summary.append(
            String.format(
                Locale.US, "打呼：%d 段，共約 %.1f 分鐘（佔監測時間 %.1f%%）\n",
                s.count(Session.TYPE_SNORE), s.seconds(Session.TYPE_SNORE) / 60.0, s.snorePercent()
            )
        )
        summary.append("說話／夢話：").append(s.count(Session.TYPE_TALK)).append(" 次\n")
        summary.append("其他聲響：").append(s.count(Session.TYPE_OTHER)).append(" 次\n")
        summary.append("環境噪音（車聲等，不扣分）：").append(s.count(Session.TYPE_NOISE)).append(" 次\n")
        summary.append("安靜時間：").append(s.quietPercent()).append("%")
        scoreCard.addView(Ui.text(this, summary.toString(), 15f, Ui.TEXT), Ui.full(this, 8))
        root.addView(scoreCard, Ui.full(this, 16))

        // 圖表
        val chartCard = Ui.card(this)
        chartCard.addView(Ui.text(this, "整晚音量變化", 16f, Ui.TEXT, bold = true))
        chartCard.addView(NightChartView(this, s), Ui.full(this, 10, Ui.dp(this, 170)))
        val legend = Ui.text(
            this,
            "長條：每分鐘音量（淺色為最大值）\n色塊：橘＝打呼　藍＝說話　淺灰＝其他　深灰＝環境噪音" +
                if (s.delayMin > 0) "\n開頭較暗的區域為延遲偵測時段" else "",
            12f, Ui.DIM
        )
        chartCard.addView(legend, Ui.full(this, 6))
        root.addView(chartCard, Ui.full(this, 16))

        // 篩選按鈕
        root.addView(Ui.text(this, "聲音片段", 18f, Ui.TEXT, bold = true), Ui.full(this, 24))
        val filterRow = LinearLayout(this)
        filterRow.orientation = LinearLayout.HORIZONTAL
        val options = listOf<Pair<String?, String>>(
            Pair(null, "全部"),
            Pair(Session.TYPE_SNORE, "打呼"),
            Pair(Session.TYPE_TALK, "說話"),
            Pair(Session.TYPE_NOISE, "噪音"),
            Pair(Session.TYPE_OTHER, "其他")
        )
        for ((type, label) in options) {
            val b = Ui.button(this, label, Ui.CARD)
            b.textSize = 13f
            b.setPadding(0, 0, 0, 0)
            b.setOnClickListener {
                filter = type
                renderEvents()
            }
            val lp = LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f)
            lp.marginEnd = Ui.dp(this, 4)
            filterRow.addView(b, lp)
            filterButtons.add(Pair(type, b))
        }
        root.addView(filterRow, Ui.full(this, 8))

        eventsBox = LinearLayout(this)
        eventsBox.orientation = LinearLayout.VERTICAL
        root.addView(eventsBox, Ui.full(this, 4))

        // 提醒
        val note = Ui.card(this)
        note.addView(Ui.text(this, "小提醒", 16f, Ui.TEXT, bold = true))
        note.addView(
            Ui.text(
                this,
                "這個分數只依聲音與睡眠時長估算，不能取代醫療檢查。\n" +
                    "如果打呼很大聲、常常整晚都在打呼，或錄音中聽到呼吸停頓後突然大聲喘氣，" +
                    "可能是睡眠呼吸中止症的徵兆，建議到醫院的睡眠中心或耳鼻喉科諮詢。",
                14f, Ui.DIM
            ),
            Ui.full(this, 6)
        )
        root.addView(note, Ui.full(this, 24))

        val del = Ui.button(this, "刪除這晚的紀錄", Ui.DANGER)
        del.setOnClickListener { confirmDelete() }
        root.addView(del, Ui.full(this, 20, Ui.dp(this, 52)))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.addView(root)
        setContentView(scroll)

        renderEvents()
    }

    override fun onPause() {
        super.onPause()
        stopPlayback()
    }

    private fun renderEvents() {
        stopPlayback()
        for ((type, b) in filterButtons) {
            b.background = Ui.rounded(if (type == filter) Ui.ACCENT_DARK else Ui.CARD, Ui.dp(this, 12))
        }
        eventsBox.removeAllViews()
        val list = session.events.filter { filter == null || it.type == filter }
        if (list.isEmpty()) {
            eventsBox.addView(Ui.text(this, "沒有這類的聲音片段", 14f, Ui.DIM), Ui.full(this, 10))
            return
        }
        for (e in list) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = Ui.rounded(Ui.CARD, Ui.dp(this, 12))
            val p = Ui.dp(this, 12)
            row.setPadding(p, p, p, p)

            val dot = TextView(this)
            dot.text = "●"
            dot.textSize = 18f
            dot.setTextColor(Session.typeColor(e.type))
            row.addView(dot)

            val info = Ui.text(
                this,
                String.format(
                    Locale.US, "%s　%s\n有聲音約 %.1f 秒・最大音量 %d",
                    Ui.clock(session.start + e.offsetMs), Session.typeName(e.type), e.durationSec, e.peak
                ),
                14f, Ui.TEXT
            )
            val infoLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            infoLp.marginStart = Ui.dp(this, 10)
            row.addView(info, infoLp)

            val fix = Ui.button(this, "改", Ui.CARD)
            fix.textSize = 13f
            fix.background = Ui.rounded(0xFF2A3360.toInt(), Ui.dp(this, 10))
            fix.setPadding(0, 0, 0, 0)
            fix.setOnClickListener { relabel(e) }
            val fixLp = LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 40))
            fixLp.marginEnd = Ui.dp(this, 6)
            row.addView(fix, fixLp)

            val f = File(session.dir, e.file)
            if (e.file.isNotEmpty() && f.exists()) {
                val play = Ui.button(this, "▶ 播放", Ui.ACCENT_DARK)
                play.textSize = 14f
                play.setOnClickListener { togglePlay(f, play) }
                row.addView(play)
            } else {
                row.addView(Ui.text(this, if (e.type == Session.TYPE_NOISE) "未存\n錄音" else "錄音\n已刪除", 12f, Ui.DIM))
            }
            eventsBox.addView(row, Ui.full(this, 8))
        }
    }

    /** 修正分類，並讓 App 記住，之後遇到類似的聲音自動套用 */
    private fun relabel(e: SoundEvent) {
        val types = Session.ALL_TYPES
        val names = Array(types.size) { Session.typeName(types[it]) }
        AlertDialog.Builder(this)
            .setTitle("這段其實是？")
            .setItems(names) { _, which ->
                val t = types[which]
                if (t != e.type) {
                    e.type = t
                    try { session.save() } catch (_: Exception) { }
                    Learner.add(this, e.features, t)
                    Toast.makeText(this, "已修正，之後類似的聲音會自動判斷為「${Session.typeName(t)}」", Toast.LENGTH_LONG).show()
                    val i = intent
                    finish()
                    startActivity(i)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun togglePlay(file: File, button: Button) {
        if (playingButton === button) {
            stopPlayback()
            return
        }
        stopPlayback()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { stopPlayback() }
            mp.prepare()
            mp.start()
            player = mp
            playingButton = button
            button.text = "■ 停止"
        } catch (e: Exception) {
            Toast.makeText(this, "無法播放這段錄音", Toast.LENGTH_SHORT).show()
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        playingButton?.text = "▶ 播放"
        playingButton = null
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("刪除這晚的紀錄？")
            .setMessage("這晚的所有錄音也會一起刪除，無法復原。")
            .setPositiveButton("刪除") { _, _ ->
                stopPlayback()
                session.dir.deleteRecursively()
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
