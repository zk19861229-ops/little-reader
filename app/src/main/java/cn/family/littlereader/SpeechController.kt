package cn.family.littlereader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Uses an installed offline English voice; it never silently falls back to a network voice. */
class SpeechController(private val context: Context, private val report: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { if (it < 0) stop() }.build()
    private var engine: TextToSpeech? = null
    private var initialized = false
    private var closed = false
    private var player: MediaPlayer? = null
    private var generation = 0
    private var pending: (() -> Unit)? = null
    private var pendingFile: File? = null
    private var pendingDestination: File? = null
    private val cache = File(context.cacheDir, "speech").apply { mkdirs() }
    var voiceDescription = "正在检查英语语音"
        private set

    init { initialize() }
    private fun initialize() {
        initialized = false
        engine = TextToSpeech(context.applicationContext) { result -> handler.post {
            if (closed) return@post
            initialized = result == TextToSpeech.SUCCESS
            if (initialized) {
                engine?.setAudioAttributes(attributes)
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { handler.post {
                        if (id != generation.toString() || closed) return@post
                        val file = pendingFile; pendingFile = null
                        val destination = pendingDestination; pendingDestination = null
                        val after = pending; pending = null
                        if (file != null && file.length() > 44) {
                            val complete = if (destination != null && file.renameTo(destination)) destination else file
                            play(complete, "正在听示范", after)
                        }
                        else { report("声音未生成成功，请检查英语语音包"); abandonFocus() }
                    } }
                    @Deprecated("Legacy TTS error callback")
                    override fun onError(id: String?) { handler.post {
                        if (id == generation.toString()) {
                            pendingFile?.delete(); pendingFile = null; pendingDestination = null; pending = null
                            report("声音未生成成功，请到家长设置检查英语语音包"); abandonFocus()
                        }
                    } }
                })
            }
            updateDescription()
        } }
    }
    private fun updateDescription() {
        val exists = initialized && engine?.voices.orEmpty().any { it.locale.language == "en" && !it.isNetworkConnectionRequired }
        voiceDescription = if (exists) "已发现离线英语声音" else "需要安装离线英语语音包"
    }
    fun refreshVoice() {
        if (closed) return
        if (!initialized && engine != null) return
        updateDescription()
    }
    fun reinitialize() { stop(); engine?.shutdown(); engine = null; initialize() }

    fun speak(text: String, slow: Boolean, accent: String, after: (() -> Unit)? = null) {
        stop()
        if (closed) return
        val token = generation
        val tts = engine
        val voices = if (initialized) tts?.voices.orEmpty().filter { it.locale.language == "en" && !it.isNetworkConnectionRequired } else emptyList()
        val voice = voices.filter { it.locale.country == accent }.maxByOrNull { it.quality }
        if (voice == null || tts == null) {
            // A small bundled experience page works even before parents install a speech engine.
            val sample = demoAudio(text, slow)
            if (sample != null && accent == "US") { play(sample, "正在听示范", after); return }
            report(if (!initialized) "英语声音尚未就绪，请到家长设置检查语音引擎"
                else "未找到${if (accent == "GB") "英式" else "美式"}离线声音，请到家长设置安装语音包")
            // A missing demo voice must not prevent children hearing their own recording.
            if (after != null) handler.postDelayed({ if (token == generation && !closed) after() }, 700)
            return
        }
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            report("声音正被其他应用使用，请稍后再试"); return
        }
        val language = if (accent == "GB") Locale.UK else Locale.US
        if (tts.setLanguage(language) < TextToSpeech.LANG_AVAILABLE || tts.setVoice(voice) == TextToSpeech.ERROR) {
            report("英语语音包未准备好，请到家长设置检查"); abandonFocus(); return
        }
        tts.setSpeechRate(if (slow) .65f else .9f)
        val key = MessageDigest.getInstance("SHA-256").digest("${voice.name}|$slow|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(cache, "$key.wav")
        if (file.length() > 44) { play(file, "正在听示范", after); return }
        trimCache()
        val temporary = File(cache, "$key-$token.pending.wav")
        pending = after; pendingFile = temporary; pendingDestination = file
        report("正在准备${if (slow) "慢速" else "示范"}声音…")
        if (tts.synthesizeToFile(text, Bundle(), temporary, token.toString()) == TextToSpeech.ERROR) {
            pendingFile = null; pendingDestination = null; pending = null; temporary.delete(); abandonFocus()
            report("声音未生成成功，请检查英语语音包")
        }
        handler.postDelayed({
            if (token == generation && pendingFile != null && !closed) {
                stop(); report("英语声音准备超时，请到家长设置检查语音包")
            }
        }, 20000)
    }

    private fun demoAudio(text: String, slow: Boolean): File? {
        val index = when (text.lowercase(Locale.US).trim()) {
            "we can see the bear go in the river!" -> "sentence"
            "we", "can", "see", "the", "bear", "go", "in", "river" -> text.lowercase(Locale.US).trim()
            else -> return null
        }
        val name = "${index}${if (slow) "-slow" else ""}.mp3"
        val file = File(cache, "demo-$name")
        return runCatching {
            if (!file.isFile) context.assets.open("audio/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            file
        }.getOrNull()
    }
    fun playRecording(file: File) { stop(); play(file, "现在听听你的声音", null) }
    private fun play(file: File, message: String, after: (() -> Unit)?) {
        if (closed) return
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            report("声音正被其他应用使用，请稍后再试"); return
        }
        val token = generation
        val next = MediaPlayer()
        player?.release(); player = next
        try {
            next.setAudioAttributes(attributes)
            next.setDataSource(file.absolutePath)
            next.setOnPreparedListener {
                if (token != generation || closed) return@setOnPreparedListener
                report(message); it.start()
            }
            next.setOnCompletionListener {
                if (player === it) player = null
                it.release()
                if (token == generation && !closed) {
                    report("听完啦，可以再读一次")
                    if (after != null) handler.postDelayed({ if (token == generation && !closed) after() }, 450)
                    else abandonFocus()
                }
            }
            next.setOnErrorListener { mp, _, _ ->
                if (player === mp) player = null
                mp.release(); abandonFocus(); report("这段声音无法播放，请重新生成或录音"); true
            }
            next.prepareAsync()
        } catch (_: Exception) { if (player === next) player = null; next.release(); abandonFocus(); report("这段声音无法播放，请再试一次") }
    }
    private fun trimCache() {
        val files = cache.listFiles().orEmpty().filter { !it.name.startsWith("demo-") }.sortedBy { it.lastModified() }
        var bytes = files.sumOf { it.length() }
        for (file in files) { if (bytes < 60 * 1024 * 1024) break; bytes -= file.length(); file.delete() }
    }
    private fun abandonFocus() { audioManager.abandonAudioFocusRequest(focusRequest) }
    fun stop() {
        generation++; pending = null
        engine?.stop(); pendingFile?.delete(); pendingFile = null; pendingDestination = null
        player?.let { runCatching { it.stop() }; it.release() }; player = null
        abandonFocus()
    }
    fun close() { closed = true; stop(); engine?.shutdown(); handler.removeCallbacksAndMessages(null) }
}
