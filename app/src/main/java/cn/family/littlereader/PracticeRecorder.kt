package cn.family.littlereader

import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/** One short recording per sentence; never overwrite an existing take with a failed recording. */
class PracticeRecorder(private val onLimit: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var temporary: File? = null
    private var started = 0L
    val isRecording get() = recorder != null
    private val timeout = Runnable { onLimit() }

    @Suppress("DEPRECATION")
    fun start(file: File) {
        cancel()
        val temp = File(file.parentFile, "take.pending.m4a")
        val next = MediaRecorder()
        try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioSamplingRate(44100)
            next.setAudioEncodingBitRate(96000)
            next.setOutputFile(temp.absolutePath)
            next.prepare(); next.start()
            recorder = next; target = file; temporary = temp; started = SystemClock.elapsedRealtime()
            handler.postDelayed(timeout, 15000)
        } catch (e: Exception) { next.release(); temp.delete(); throw e }
    }

    fun finish(): Boolean {
        val current = recorder ?: return false
        handler.removeCallbacks(timeout); recorder = null
        val valid = runCatching { current.stop(); SystemClock.elapsedRealtime() - started >= 600 }.getOrDefault(false)
        current.release()
        val temp = temporary; val file = target
        temporary = null; target = null
        if (!valid || temp == null || file == null || temp.length() < 200) { temp?.delete(); return false }
        if (!temp.renameTo(file)) { temp.delete(); return false }
        return true
    }

    fun cancel() {
        handler.removeCallbacks(timeout)
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null; temporary?.delete(); temporary = null; target = null
    }
}
