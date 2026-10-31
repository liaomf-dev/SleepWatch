package com.example.sleepwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 整夜在背景監聽麥克風。
 * 平常只計算音量，不存錄音；聲音明顯高於環境噪音時，才把那一段（含前 2 秒）存成 WAV 檔，
 * 並依聲音特徵粗分為「打呼 / 說話 / 其他聲響」。
 */
class SleepService : Service() {

    companion object {
        const val ACTION_START = "com.example.sleepwatch.START"
        const val ACTION_STOP = "com.example.sleepwatch.STOP"
        const val EXTRA_DELAY = "delay"

        @Volatile var running = false
        @Volatile var startTime = 0L
        @Volatile var level = 0
        @Volatile var eventCount = 0
        @Volatile var detecting = false
        @Volatile var error: String? = null
        @Volatile var lastFinishedId: String? = null

        private const val CHANNEL = "sleep_watch"
        private const val NOTIF_ID = 1
        private const val RATE = 16000
        private const val FRAME = 1600            // 每格 0.1 秒
        private const val PRE_FRAMES = 20         // 事件前多保留 2 秒
        private const val END_QUIET_FRAMES = 40   // 安靜 4 秒視為事件結束
        private const val MAX_CLIP_FRAMES = 300   // 單段錄音最長 30 秒
        private const val MIN_LOUD_FRAMES = 4     // 少於 0.4 秒的聲音忽略
        private const val MAX_CLIPS = 120         // 一晚最多存 120 段錄音
    }

    private var thread: Thread? = null
    @Volatile private var stopFlag = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var session: Session? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) begin(intent.getIntExtra(EXTRA_DELAY, 0)) else startForegroundCompat()
            ACTION_STOP -> finishRecording()
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running) finishRecording()
        super.onDestroy()
    }

    private fun begin(delayMin: Int) {
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            error = "無法啟動背景錄音：${e.message}"
            stopSelf()
            return
        }
        val now = System.currentTimeMillis()
        val dir = File(Session.root(this), now.toString())
        dir.mkdirs()
        val s = Session(dir)
        s.start = now
        s.end = now
        s.delayMin = delayMin
        s.save()
        session = s

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepWatch:record")
        wl.acquire(14L * 3_600_000L)
        wakeLock = wl

        stopFlag = false
        error = null
        eventCount = 0
        level = 0
        detecting = delayMin == 0
        startTime = now
        running = true

        val t = Thread({
            recordLoop(s, delayMin * 60_000L)
            if (!stopFlag) {
                // 錄音因錯誤中斷
                Handler(Looper.getMainLooper()).post { finishRecording() }
            }
        }, "sleep-record")
        thread = t
        t.start()
    }

    private fun finishRecording() {
        if (!running) {
            stopSelf()
            return
        }
        stopFlag = true
        try {
            thread?.join(5000)
        } catch (_: InterruptedException) {
        }
        thread = null
        val s = session
        if (s != null) {
            if (s.minuteAvg.isEmpty() && s.events.isEmpty()) {
                s.dir.deleteRecursively()
            } else {
                lastFinishedId = s.id
            }
            try { Session.cleanupOldClips(this, Prefs.retentionDays(this), s.id) } catch (_: Exception) { }
        }
        session = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        running = false
        detecting = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder: Notification.Builder
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "睡眠觀察", NotificationManager.IMPORTANCE_LOW)
            )
            builder = Notification.Builder(this, CHANNEL)
        } else {
            builder = Notification.Builder(this)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = builder
            .setContentTitle("睡眠觀察中")
            .setContentText("正在記錄打呼與夢話，點此查看或停止")
            .setSmallIcon(R.drawable.ic_moon)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------------- 錄音與分析 ----------------

    private fun recordLoop(session: Session, delayMs: Long) {
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, FRAME * 8)
            )
        } catch (e: Exception) {
            error = "無法使用麥克風（請確認已允許麥克風權限）"
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error = "麥克風初始化失敗，可能正被其他 App 使用"
            return
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            error = "無法開始錄音"
            return
        }

        val buf = ShortArray(FRAME)
        val pre = ArrayDeque<ShortArray>()
        val lpA = 1.0 - exp(-2.0 * PI * 400.0 / RATE)   // 400 Hz 低通濾波係數
        var lp = 0.0
        var floor = -1.0
        var frameIndex = 0L
        var minuteSum = 0.0
        var minuteN = 0
        var minuteMax = 0
        var clipsSaved = 0
        var lastSave = System.currentTimeMillis()

        var inEvent = false
        var clip = ByteArrayOutputStream()
        var evStart = 0L
        var clipFrames = 0
        var quietRun = 0
        var peak = 0
        var analyzer = EventAnalyzer(RATE)
        val trigger = Prefs.triggerDb(this)
        val saveNoise = Prefs.saveNoise(this)
        val learned = Learner.load(this)

        fun endEvent() {
            inEvent = false
            val a = analyzer
            if (a.loudCount < MIN_LOUD_FRAMES) return
            val f = a.features()
            // 先看使用者修正過、很像的例子；沒有的話用規則判斷
            val type = Learner.match(learned, f) ?: a.classify(f)
            var fileName = ""
            val wantClip = type != Session.TYPE_NOISE || saveNoise
            if (wantClip && clipsSaved < MAX_CLIPS) {
                fileName = "clip_$evStart.wav"
                try {
                    writeWav(File(session.dir, fileName), clip.toByteArray())
                    clipsSaved++
                } catch (e: Exception) {
                    fileName = ""
                }
            }
            session.events.add(
                SoundEvent(evStart.coerceAtLeast(0L), a.loudCount / 10f, type, peak, fileName, f)
            )
            eventCount = session.events.size
        }

        while (!stopFlag) {
            var n = 0
            var failed = false
            while (n < FRAME && !stopFlag) {
                val r = rec.read(buf, n, FRAME - n)
                if (r < 0) {
                    failed = true
                    break
                }
                n += r
            }
            if (failed) {
                error = "錄音中斷"
                break
            }
            if (n < FRAME) break

            val frame = buf.copyOf()
            var sum = 0.0
            var lowE = 0.0
            var zc = 0
            for (i in 0 until FRAME) {
                val v = frame[i].toDouble()
                sum += v * v
                lp += lpA * (v - lp)
                lowE += lp * lp
                if (i > 0 && (frame[i] >= 0) != (frame[i - 1] >= 0)) zc++
            }
            val rms = sqrt(sum / FRAME)
            val db = (20.0 * log10(max(rms, 1.0) / 32768.0) + 96.0).roundToInt().coerceIn(0, 96)
            level = db
            val lowRatio = if (sum > 0.0) lowE / sum else 0.0
            val zcr = zc * (RATE.toDouble() / FRAME)

            // 每分鐘的平均與最大音量
            minuteSum += db
            minuteN++
            if (db > minuteMax) minuteMax = db
            if (minuteN >= 600) {
                session.minuteAvg.add((minuteSum / minuteN).roundToInt())
                session.minuteMax.add(minuteMax)
                minuteSum = 0.0
                minuteN = 0
                minuteMax = 0
            }

            // 環境噪音基準：變安靜時快速下降、變吵時慢慢上升
            if (floor < 0) floor = db.toDouble()
            floor += if (db < floor) (db - floor) * 0.05 else (db - floor) * 0.002
            val loud = db > max(floor, 20.0) + trigger

            val offset = frameIndex * 100L
            frameIndex++
            val detectNow = offset >= delayMs
            detecting = detectNow

            if (detectNow && !inEvent && loud) {
                inEvent = true
                evStart = offset - pre.size * 100L
                clip = ByteArrayOutputStream()
                for (p in pre) writePcm(clip, p)
                clipFrames = pre.size
                quietRun = 0
                peak = 0
                analyzer = EventAnalyzer(RATE)
            }
            if (inEvent) {
                if (clipFrames < MAX_CLIP_FRAMES) {
                    writePcm(clip, frame)
                    clipFrames++
                }
                analyzer.add(frame, loud, zcr, lowRatio)
                if (loud) {
                    if (db > peak) peak = db
                    quietRun = 0
                } else {
                    quietRun++
                }
                if (quietRun >= END_QUIET_FRAMES || clipFrames >= MAX_CLIP_FRAMES) endEvent()
            }

            pre.addLast(frame)
            if (pre.size > PRE_FRAMES) pre.removeFirst()

            val now = System.currentTimeMillis()
            if (now - lastSave > 5 * 60_000L) {
                session.end = now
                try { session.save() } catch (_: Exception) { }
                lastSave = now
            }
        }

        if (inEvent) endEvent()
        if (minuteN >= 100) {
            session.minuteAvg.add((minuteSum / minuteN).roundToInt())
            session.minuteMax.add(minuteMax)
        }
        try { rec.stop() } catch (_: Exception) { }
        rec.release()
        session.end = System.currentTimeMillis()
        try { session.save() } catch (_: Exception) { }
    }
}

private fun writePcm(out: ByteArrayOutputStream, data: ShortArray) {
    for (s in data) {
        val v = s.toInt()
        out.write(v and 0xff)
        out.write((v shr 8) and 0xff)
    }
}

private fun writeWav(file: File, pcm: ByteArray) {
    val rate = 16000
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray(Charsets.US_ASCII))
    h.putInt(36 + pcm.size)
    h.put("WAVE".toByteArray(Charsets.US_ASCII))
    h.put("fmt ".toByteArray(Charsets.US_ASCII))
    h.putInt(16)
    h.putShort(1.toShort())       // PCM
    h.putShort(1.toShort())       // 單聲道
    h.putInt(rate)
    h.putInt(rate * 2)
    h.putShort(2.toShort())
    h.putShort(16.toShort())
    h.put("data".toByteArray(Charsets.US_ASCII))
    h.putInt(pcm.size)
    FileOutputStream(file).use {
        it.write(h.array())
        it.write(pcm)
    }
}
