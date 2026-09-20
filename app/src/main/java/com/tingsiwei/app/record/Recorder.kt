package com.tingsiwei.app.record

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音器：m4a/AAC 16kHz 单声道 32kbps（一小时约 14MB，语音识别效果足够）。
 * 支持暂停/继续（Android 7.0+）。
 */
class Recorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var running = false
    private var paused = false

    private var startStamp = 0L
    private var pausedTotalMs = 0L
    private var pauseStamp = 0L

    val isRunning: Boolean get() = running
    val isPaused: Boolean get() = paused

    fun recordingsDir(): File = File(context.filesDir, "recordings").apply { mkdirs() }

    fun start(): File {
        check(!running) { "已经在录音了" }
        val dir = recordingsDir()
        val name = "REC_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())}.m4a"
        val file = File(dir, name)
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(16000)
        r.setAudioEncodingBitRate(32000)
        r.setAudioChannels(1)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        outFile = file
        running = true
        paused = false
        startStamp = SystemClock.elapsedRealtime()
        pausedTotalMs = 0
        pauseStamp = 0
        return file
    }

    fun pause() {
        val r = recorder ?: return
        if (!running || paused) return
        try {
            r.pause()
        } catch (_: Exception) {
            // 少数机型不支持暂停：不崩也不改状态，录音和计时照常继续
            return
        }
        paused = true
        pauseStamp = SystemClock.elapsedRealtime()
    }

    fun resume() {
        val r = recorder ?: return
        if (!running || !paused) return
        try {
            r.resume()
        } catch (_: Exception) {
            paused = false
            return
        }
        paused = false
        pausedTotalMs += SystemClock.elapsedRealtime() - pauseStamp
    }

    fun currentDurationMs(): Long {
        if (!running) return 0
        val now = SystemClock.elapsedRealtime()
        val pausedNow = if (paused) now - pauseStamp else 0
        return now - startStamp - pausedTotalMs - pausedNow
    }

    /** @return 录音文件与时长 */
    fun stop(): Pair<File, Long> {
        val r = recorder ?: error("没有在录音")
        val duration = currentDurationMs()
        running = false
        paused = false
        try {
            r.stop()
        } catch (e: Exception) {
            // stop 失败（如录音过短）文件不可用，清掉避免残留垃圾
            outFile?.delete()
            throw e
        } finally {
            r.release()
            recorder = null
        }
        val file = outFile ?: error("没有录音文件")
        return file to duration
    }

    /** 出错或取消时清理，不保留文件 */
    fun cancel() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        running = false
        paused = false
        outFile?.delete()
        outFile = null
    }
}
