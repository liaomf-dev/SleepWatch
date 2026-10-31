package com.example.sleepwatch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var mainButton: Button
    private lateinit var statusText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var delayCheck: CheckBox
    private lateinit var listBox: LinearLayout
    private var wasRunning = false
    private var openAfterStop = false

    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val root = Ui.column(this, 20)
        root.addView(Ui.text(this, "睡眠觀察", 30f, Ui.TEXT, bold = true))
        root.addView(Ui.text(this, "記錄打呼、說夢話與夜間聲響", 15f, Ui.DIM))

        // 記錄控制卡片
        val card = Ui.card(this)
        statusText = Ui.text(this, "", 17f, Ui.TEXT)
        card.addView(statusText)

        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        levelBar.max = 90
        levelBar.progressTintList = ColorStateList.valueOf(Ui.ACCENT)
        card.addView(levelBar, Ui.full(this, 12))

        delayCheck = CheckBox(this)
        delayCheck.text = "15 分鐘後才開始偵測（避免錄到入睡前的聲音）"
        delayCheck.setTextColor(Ui.DIM)
        delayCheck.textSize = 14f
        delayCheck.isChecked = true
        delayCheck.buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
        card.addView(delayCheck, Ui.full(this, 8))

        mainButton = Ui.button(this, "開始睡眠觀察", Ui.ACCENT_DARK)
        mainButton.textSize = 18f
        mainButton.setOnClickListener { onMainButton() }
        card.addView(mainButton, Ui.full(this, 12, Ui.dp(this, 56)))
        root.addView(card, Ui.full(this, 20))

        // 歷史紀錄
        root.addView(Ui.text(this, "過去的紀錄", 18f, Ui.TEXT, bold = true), Ui.full(this, 28))
        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        root.addView(listBox, Ui.full(this, 4))

        // 使用說明
        val tips = Ui.card(this)
        tips.addView(Ui.text(this, "使用方式", 16f, Ui.TEXT, bold = true))
        tips.addView(
            Ui.text(
                this,
                "1. 睡前按「開始睡眠觀察」，把手機放在枕頭旁（約 30–50 公分），麥克風朝向你。\n" +
                    "2. 建議接上充電器，並把手機設為勿擾模式。\n" +
                    "3. 起床後按「停止並查看結果」，可以重播每一段打呼或說話的錄音。\n" +
                    "4. 平常只計算音量，只有偵測到明顯聲音時才會存下該段錄音，錄音只存在這支手機裡。\n" +
                    "5. 打呼／說話的分類是依聲音特徵自動判斷，不一定完全準確，可以播放錄音確認。",
                14f, Ui.DIM
            ),
            Ui.full(this, 6)
        )
        root.addView(tips, Ui.full(this, 28))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        wasRunning = SleepService.running
        refreshList()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun onMainButton() {
        if (SleepService.running) {
            openAfterStop = true
            mainButton.isEnabled = false
            val i = Intent(this, SleepService::class.java)
            i.action = SleepService.ACTION_STOP
            startService(i)
        } else {
            val needed = ArrayList<String>()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (needed.isEmpty()) startRecording() else requestPermissions(needed.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            Toast.makeText(this, "需要麥克風權限才能記錄打呼與夢話", Toast.LENGTH_LONG).show()
        }
    }

    private fun startRecording() {
        val i = Intent(this, SleepService::class.java)
        i.action = SleepService.ACTION_START
        i.putExtra(SleepService.EXTRA_DELAY, if (delayCheck.isChecked) 15 else 0)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        handler.postDelayed({ updateStatus() }, 300)
    }

    private fun updateStatus() {
        val running = SleepService.running
        if (running) {
            val sec = (System.currentTimeMillis() - SleepService.startTime) / 1000
            val phase = if (SleepService.detecting) "偵測中" else "等待入睡（尚未開始偵測）"
            statusText.text = String.format(
                "● 記錄中　%d:%02d:%02d\n%s・已記錄 %d 個聲音事件",
                sec / 3600, (sec / 60) % 60, sec % 60, phase, SleepService.eventCount
            )
            levelBar.visibility = View.VISIBLE
            levelBar.progress = SleepService.level
            mainButton.text = "停止並查看結果"
            mainButton.isEnabled = true
            delayCheck.isEnabled = false
        } else {
            val err = SleepService.error
            statusText.text = if (err != null) "上次記錄中斷：$err" else "準備好了。睡前按下開始。"
            levelBar.visibility = View.GONE
            mainButton.text = "開始睡眠觀察"
            mainButton.isEnabled = true
            delayCheck.isEnabled = true
            if (wasRunning) {
                refreshList()
                val id = SleepService.lastFinishedId
                if (openAfterStop && id != null) openNight(id)
                openAfterStop = false
            }
        }
        wasRunning = running
    }

    private fun refreshList() {
        listBox.removeAllViews()
        val sessions = Session.all(this).filter { !(SleepService.running && it.start == SleepService.startTime) }
        if (sessions.isEmpty()) {
            listBox.addView(Ui.text(this, "還沒有紀錄", 14f, Ui.DIM), Ui.full(this, 8))
            return
        }
        for (s in sessions) {
            val row = Ui.card(this)
            row.addView(Ui.text(this, Ui.dateRange(s), 16f, Ui.TEXT, bold = true))
            val line = "分數 ${s.score()}（${Ui.scoreLabel(s.score())}）・" +
                "打呼 ${s.count(Session.TYPE_SNORE)} 段・說話 ${s.count(Session.TYPE_TALK)} 次・" +
                Ui.durationText(s.end - s.start)
            row.addView(Ui.text(this, line, 14f, Ui.DIM), Ui.full(this, 4))
            row.isClickable = true
            row.setOnClickListener { openNight(s.id) }
            listBox.addView(row, Ui.full(this, 10))
        }
    }

    private fun openNight(id: String) {
        val i = Intent(this, NightActivity::class.java)
        i.putExtra(NightActivity.EXTRA_ID, id)
        startActivity(i)
    }

    companion object {
        private const val REQ_PERMS = 10
    }
}
