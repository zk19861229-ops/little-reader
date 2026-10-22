package cn.family.littlereader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val green = Color.rgb(40, 120, 93)
    private val ink = Color.rgb(34, 57, 48)
    private val muted = Color.rgb(105, 118, 109)
    private val cream = Color.rgb(247, 245, 238)
    private val lightGreen = Color.rgb(229, 240, 229)
    private val orange = Color.rgb(248, 220, 176)
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: PageStore
    private lateinit var speech: SpeechController
    private lateinit var recorder: PracticeRecorder
    private lateinit var screen: LinearLayout
    private lateinit var scroller: ScrollView
    private var page: ReadingPage? = null
    private var selected = 0
    private var status: TextView? = null
    private var recordButton: Button? = null
    private var compareButton: Button? = null
    private var sentenceText: TextView? = null
    private var wordRows: LinearLayout? = null
    private var pageImage: PageImageView? = null
    private var sentenceButtons = mutableListOf<Button>()
    private var cameraFile: File? = null
    private var loading = false
    private var loadGeneration = 0
    private var screenName = "shelf"
    private var displayedBitmap: Bitmap? = null
    private val preferences by lazy { getSharedPreferences("reader", MODE_PRIVATE) }
    private val microphoneCode = 401

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        store = PageStore(File(filesDir, "pages"))
        speech = SpeechController(this) { message -> runOnUiThread { setStatus(message) } }
        recorder = PracticeRecorder { finishRecording() }
        cameraFile = state?.getString("cameraFile")?.let { File(it) }
        seedDemo()
        val restored = state?.getString("pageId")?.let { store.get(it) }
        if (restored != null) { selected = state.getInt("selected"); showReader(restored) }
        else showShelf()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 20, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (border != null) setStroke(dp(1), border)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(text: String, size: Float = 16f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(text: String, color: Int = lightGreen, onClick: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 18f; isAllCaps = false
        setTextColor(if (color == green) Color.WHITE else ink)
        background = rounded(color, 17); minHeight = dp(56)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { onClick() }
    }
    private fun LinearLayout.add(view: View, top: Int = 0, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
        addView(view, LinearLayout.LayoutParams(-1, if (height >= 0) dp(height) else height).apply { topMargin = dp(top) })
    }
    private fun card(color: Int = Color.WHITE) = column().apply { background = rounded(color); setPadding(dp(18), dp(18), dp(18), dp(18)) }
    private fun rowButtons(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEachIndexed { i, child ->
            addView(child, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(10) })
        }
    }
    private fun beginScreen(name: String, title: String, subtitle: String, back: Boolean = false) {
        screenName = name
        status = null; sentenceText = null; wordRows = null; pageImage = null
        recordButton = null; compareButton = null; sentenceButtons.clear()
        scroller = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(cream) }
        screen = column().apply { setPadding(dp(22), dp(14), dp(22), dp(28)) }
        scroller.addView(screen); setContentView(scroller)
        if (back) screen.add(button("‹  返回书页", cream) { showShelf() })
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(title, 28f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (!back) header.addView(button("家长", cream) { parentGate() }, LinearLayout.LayoutParams(dp(76), dp(56)))
        screen.add(header, 8); screen.add(label(subtitle, 14f, muted), 6)
    }

    private fun showShelf() {
        stopAudio(); page = null; selected = 0
        beginScreen("shelf", "小小点读", "每天读一点，英语更亲近")
        val hero = card(lightGreen)
        hero.add(label("今天，读哪一页？", 25f, ink, true))
        hero.add(label("拍下课本，点一句，听一句。", 16f, muted), 8)
        hero.add(button("＋  拍课本", green) { takePhoto() }, 20)
        hero.add(button("从相册选一页", Color.WHITE) { choosePhoto() }, 10)
        screen.add(hero, 24)
        val pages = store.list()
        screen.add(label("最近读过", 21f, ink, true), 26)
        screen.add(label("打开书页，就能接着读", 14f, muted), 5)
        pages.take(30).forEach { item ->
            val row = card()
            val title = if (item.id == "demo") "小熊过河 · 体验页" else item.title
            row.add(label(title, 20f, ink, true))
            row.add(label(item.sentences.firstOrNull()?.text ?: "待补充英文", 16f, muted), 6)
            row.add(label("${item.sentences.size} 句英文  ·  ${SimpleDateFormat("M月d日", Locale.CHINA).format(Date(item.createdAt))}", 13f, muted), 10)
            row.add(button("打开点读  ›", lightGreen) { selected = 0; showReader(item) }, 12)
            screen.add(row, 12)
        }
        if (pages.isEmpty()) screen.add(label("还没有书页。拍一张，开始今天的朗读吧。", 17f, muted), 20)
        screen.add(label("照片与跟读录音保存在这台手机上", 12f, muted).apply { gravity = Gravity.CENTER }, 24)
    }

    private fun showReader(item: ReadingPage) {
        stopAudio()
        page = item.copy(openedAt = System.currentTimeMillis())
        runCatching { store.save(page!!) }.onFailure { toast("书页保存失败，请检查手机存储空间") }
        selected = selected.coerceIn(0, (item.sentences.size - 1).coerceAtLeast(0))
        beginScreen("reader", "点一点，听英语", "点击图片中的框，或下方的大号句子", true)
        val bitmap = BitmapFactory.decodeFile(store.image(item.id).absolutePath)
        displayedBitmap = bitmap
        if (bitmap != null) {
            pageImage = PageImageView(this, bitmap, item.sentences).apply {
                selected = this@MainActivity.selected
                onSentence = { index -> selectSentence(index, true) }
                background = rounded(Color.WHITE)
            }
            val pageHeight = (resources.displayMetrics.heightPixels / resources.displayMetrics.density * .27f).toInt().coerceIn(170, 240)
            screen.add(pageImage!!, 16, pageHeight)
        } else if (item.sentences.any { it.boxes.isNotEmpty() }) screen.add(label("原图暂时无法打开，仍可以点读下面的句子。", 15f, muted), 20)
        val reading = card()
        reading.add(label("听一听 · 读一读", 14f, green, true))
        sentenceText = label("", 27f, ink, true)
        reading.add(sentenceText!!, 10)
        status = label("点“听示范”，然后试着跟读", 14f, muted)
        status!!.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        reading.add(status!!, 12)
        reading.add(rowButtons(button("▶  听示范", green) { speakSelected(false) }, button("慢一点", orange) { speakSelected(true) }), 16)
        recordButton = button("●  跟我读", lightGreen) { toggleRecording() }
        compareButton = button("听听我读的", cream) { compareRecording() }
        reading.add(rowButtons(recordButton!!, compareButton!!), 10)
        screen.add(reading, 18)
        screen.add(label("单词也可以点", 20f, ink, true), 22)
        wordRows = column(); screen.add(wordRows!!, 10)
        screen.add(label("这一页的句子", 20f, ink, true), 24)
        item.sentences.forEachIndexed { index, sentence ->
            val b = button("${index + 1}.  ${sentence.text}", Color.WHITE) { selectSentence(index, true) }
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            sentenceButtons.add(b); screen.add(b, 10)
        }
        screen.add(button("识别有误？家长修改", cream) { editPage() }, 18)
        refreshSelection()
    }

    private fun selectSentence(index: Int, speak: Boolean) {
        if (recorder.isRecording) { toast("请先结束这一句的录音"); return }
        speech.stop(); selected = index; refreshSelection()
        if (speak) speakSelected(false)
    }
    private fun currentSentence() = page?.sentences?.getOrNull(selected)
    private fun refreshSelection() {
        val sentence = currentSentence() ?: return
        sentenceText?.text = sentence.text; pageImage?.selected = selected
        sentenceButtons.forEachIndexed { i, b -> b.background = rounded(if (i == selected) lightGreen else Color.WHITE) }
        wordRows?.removeAllViews()
        // Two flexible columns prevent long words and larger accessibility fonts from clipping.
        sentence.words.chunked(2).forEach { words ->
            wordRows?.add(rowButtons(*words.map { word -> button(word, Color.WHITE) {
                if (!recorder.isRecording) { setStatus("正在听：$word"); speech.speak(word, false, accent()) }
            } }.toTypedArray()), 8)
        }
        val exists = page?.let { store.recording(it.id, sentence).isFile } == true
        compareButton?.isEnabled = exists
        compareButton?.alpha = if (exists) 1f else .5f
        setStatus(if (exists) "已经跟读过，可以听听自己的声音" else "点“听示范”，然后试着跟读")
    }
    private fun accent() = preferences.getString("accent", "US") ?: "US"
    private fun speakSelected(slow: Boolean) {
        if (recorder.isRecording) { toast("请先结束录音"); return }
        currentSentence()?.let { speech.speak(it.text, slow, accent()) }
    }
    private fun toggleRecording() {
        if (recorder.isRecording) { finishRecording(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (preferences.getBoolean("askedMic", false) && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                AlertDialog.Builder(this).setTitle("需要打开麦克风")
                    .setMessage("请在应用权限中允许使用麦克风，再回来跟读。")
                    .setPositiveButton("去设置") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                    .setNegativeButton("稍后", null).show()
            } else {
                preferences.edit().putBoolean("askedMic", true).apply()
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), microphoneCode)
            }
            return
        }
        val item = page ?: return; val sentence = currentSentence() ?: return
        speech.stop()
        runCatching { recorder.start(store.recording(item.id, sentence)) }.onSuccess {
            recordButton?.text = "■  读完了"
            recordButton?.background = rounded(orange)
            setStatus("正在录音，大声读出来吧（最多15秒）")
        }.onFailure { setStatus("麦克风暂时无法使用，请关闭其他录音应用后重试") }
    }
    private fun finishRecording() {
        val ok = recorder.finish()
        recordButton?.text = "●  再跟读一次"; recordButton?.background = rounded(lightGreen)
        refreshSelection()
        setStatus(if (ok) "录好了！点“听听我读的”对照一下" else "这次录音太短或没有录好，请再试一次")
    }
    private fun compareRecording() {
        if (recorder.isRecording) { toast("请先结束录音"); return }
        val item = page ?: return; val sentence = currentSentence() ?: return
        val file = store.recording(item.id, sentence)
        if (!file.isFile) { setStatus("先按“跟我读”录下你的声音"); return }
        speech.speak(sentence.text, false, accent()) { speech.playRecording(file) }
    }
    private fun stopAudio() {
        if (::speech.isInitialized) speech.stop()
        if (::recorder.isInitialized) recorder.cancel()
    }
    private fun setStatus(message: String) { status?.text = message }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun takePhoto() {
        val folder = File(cacheDir, "camera").apply { mkdirs() }
        cameraFile = File(folder, "capture-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", cameraFile!!)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newRawUri("书页照片", uri)
        }
        runCatching { startActivityForResult(intent, 101) }.onFailure { toast("暂时无法打开相机，可以从相册选择书页") }
    }
    private fun choosePhoto() {
        runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
        }, 102) }.onFailure { toast("暂时无法打开相册，请试试拍照") }
    }
    @Deprecated("Android legacy activity result API is used for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 103) { speech.reinitialize(); return }
        if (resultCode != RESULT_OK) { if (requestCode == 101) cameraFile?.delete(); return }
        when (requestCode) {
            101 -> cameraFile?.takeIf { it.length() > 0 }?.let { importPhoto(Uri.fromFile(it)) } ?: toast("没有拍到照片，请再试一次")
            102 -> data?.data?.let { importPhoto(it) }
        }
    }
    private fun importPhoto(uri: Uri) {
        if (loading) return
        loading = true; val generation = ++loadGeneration
        beginScreen("loading", "正在整理书页", "正在识别英文，请稍等一下")
        screen.add(ProgressBar(this), 40)
        screen.add(label("请保持书页平整、文字清楚。识别完成后就可以点读了。", 18f, muted), 24)
        val id = store.newId()
        worker.execute {
            val result = runCatching {
                val bitmap = PhotoLoader.import(this, uri, store.image(id))
                try {
                    val sentences = BookRecognizer().recognize(bitmap)
                    ReadingPage(id, "书页 · ${SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date())}", sentences)
                } finally { bitmap.recycle() }
            }
            runOnUiThread {
                loading = false
                cameraFile?.delete(); cameraFile = null
                if (isDestroyed || generation != loadGeneration) { store.delete(id); return@runOnUiThread }
                result.onSuccess { recognized ->
                    if (recognized.sentences.isEmpty()) {
                        store.delete(id); showShelf()
                        AlertDialog.Builder(this).setTitle("还没有找到英文")
                            .setMessage("请把镜头靠近书页，避开反光和手指，再拍一次。也可以让家长直接输入英文。")
                            .setPositiveButton("重新拍照") { _, _ -> takePhoto() }
                            .setNeutralButton("输入英文") { _, _ -> manualPage() }
                            .setNegativeButton("返回", null).show()
                    } else runCatching { store.save(recognized); selected = 0; showReader(recognized) }.onFailure {
                        store.delete(id); showShelf(); toast("保存失败，请检查手机存储空间后重试")
                    }
                }.onFailure {
                    store.delete(id); showShelf(); toast("这次没有识别成功，请换张清晰的书页重试")
                }
            }
        }
    }
    private fun parentGate(onSuccess: () -> Unit = { showSettings() }) {
        if (recorder.isRecording) { toast("请先结束录音"); return }
        val a = (3..8).random(); val b = (3..8).random()
        val input = EditText(this).apply { inputType = 2; hint = "$a + $b = ?"; textSize = 22f; setPadding(dp(22), dp(12), dp(22), dp(12)) }
        val dialog = AlertDialog.Builder(this).setTitle("请家长帮忙").setMessage("输入答案，打开家长设置。")
            .setView(input).setPositiveButton("进入", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (input.text.toString().toIntOrNull() == a + b) { dialog.dismiss(); onSuccess() }
            else input.error = "再算一下吧"
        } }
        dialog.show()
    }
    private fun showSettings() {
        stopAudio(); beginScreen("settings", "家长设置", "设置一次，孩子就能自己点读", true)
        val voiceCard = card()
        voiceCard.add(label("示范声音", 20f, ink, true))
        voiceCard.add(label("${if (accent() == "US") "美式" else "英式"}英语 · ${speech.voiceDescription}", 15f, muted), 10)
        voiceCard.add(rowButtons(button("美式英语") { preferences.edit().putString("accent", "US").apply(); showSettings() },
            button("英式英语") { preferences.edit().putString("accent", "GB").apply(); showSettings() }), 14)
        voiceCard.add(button("试听英语声音", green) { speech.speak("Hello! Let's read together.", false, accent()) }, 10)
        voiceCard.add(button("安装或设置英语语音") { openVoiceSettings() }, 10)
        status = label("如果听不到英语，请先安装英语语音包。", 14f, muted); voiceCard.add(status!!, 10)
        screen.add(voiceCard, 24)
        screen.add(button("直接输入英文", Color.WHITE) { manualPage() }, 16)
        screen.add(label("管理书页", 20f, ink, true), 24)
        store.list().forEach { item ->
            screen.add(button("删除：${item.title}", Color.WHITE) {
                AlertDialog.Builder(this).setTitle("删除这张书页？").setMessage("这张书页和对应的跟读录音会一起删除。")
                    .setPositiveButton("删除") { _, _ -> store.delete(item.id); showSettings() }
                    .setNegativeButton("保留", null).show()
            }, 10)
        }
        val info = card(lightGreen)
        info.add(label("关于小小点读", 18f, ink, true))
        info.add(label("首版提供听示范、慢速点读和跟读回放，暂不自动评价发音。\n\n书页与录音保存在本机；卸载应用会清除这些内容。", 15f, muted), 10)
        info.add(label("版本 0.1.0 · 家庭试用版", 12f, muted), 14)
        screen.add(info, 24)
    }
    private fun openVoiceSettings() {
        val intents = listOf(Intent("com.android.settings.TTS_SETTINGS"), Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA), Intent(Settings.ACTION_SETTINGS))
        for (intent in intents) { if (runCatching { startActivityForResult(intent, 103) }.isSuccess) return }
        toast("请在手机设置中搜索“文字转语音”，安装英语语音包")
    }
    private fun editPage() = parentGate {
        val original = page ?: return@parentGate
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(original.sentences.joinToString("\n") { it.text }); textSize = 19f
            minLines = 4; maxLines = 10; gravity = Gravity.TOP
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val dialog = AlertDialog.Builder(this).setTitle("修改英文，每行一句")
            .setMessage("只改文字时保留点击位置。增减句子后，请使用下方句子按钮点读。")
            .setView(input).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val lines = validateLines(input) ?: return@setOnClickListener
            val sameCount = lines.size == original.sentences.size
            val revised = original.copy(sentences = lines.mapIndexed { i, line -> ReadingSentence(line, if (sameCount) original.sentences[i].boxes else emptyList()) })
            runCatching { store.save(revised) }.onSuccess { dialog.dismiss(); selected = 0; showReader(revised) }
                .onFailure { input.error = "保存失败，请检查存储空间" }
        } }; dialog.show()
    }
    private fun validateLines(input: EditText): List<String>? {
        val lines = input.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines.size > 60 || lines.any { !Regex("[A-Za-z]").containsMatchIn(it) || it.length > 500 }) {
            input.error = "请输入英文，每行一句，每句最多500个字符，最多60句"; return null
        }
        return lines
    }
    private fun manualPage() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            hint = "Hello!\nI can read."; minLines = 4; textSize = 20f; setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val dialog = AlertDialog.Builder(this).setTitle("输入英文，每行一句").setView(input)
            .setPositiveButton("开始点读", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val lines = validateLines(input) ?: return@setOnClickListener
            val newPage = ReadingPage(store.newId(), "我的英语 · ${SimpleDateFormat("M月d日", Locale.CHINA).format(Date())}", lines.map { ReadingSentence(it, emptyList()) })
            runCatching { store.save(newPage) }.onSuccess { dialog.dismiss(); selected = 0; showReader(newPage) }
                .onFailure { input.error = "保存失败，请检查存储空间" }
        } }; dialog.show()
    }
    private fun seedDemo() {
        if (preferences.getBoolean("seeded", false)) return
        runCatching {
            val demo = ReadingPage("demo", "小熊过河", listOf(ReadingSentence("We can see the bear go in the river!", listOf(Box(240, 748, 950, 824), Box(335, 830, 850, 913)))))
            store.directory("demo").mkdirs()
            assets.open("demo.webp").use { input -> store.image("demo").outputStream().use { input.copyTo(it) } }
            store.save(demo); preferences.edit().putBoolean("seeded", true).apply()
        }
    }
    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putString("pageId", if (screenName == "reader") page?.id else null)
        state.putInt("selected", selected); state.putString("cameraFile", cameraFile?.absolutePath)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == microphoneCode) setStatus(if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) "麦克风已准备好，再点“跟我读”开始" else "未开启麦克风，仍可正常点读")
    }
    override fun onResume() { super.onResume(); if (::speech.isInitialized) speech.refreshVoice() }
    override fun onStop() {
        super.onStop()
        if (::recorder.isInitialized && recorder.isRecording) finishRecording()
        if (::speech.isInitialized) speech.stop()
    }
    @Deprecated("Legacy back behavior")
    override fun onBackPressed() {
        when (screenName) {
            "shelf" -> { super.onBackPressed() }
            "loading" -> toast("正在识别书页，请稍等")
            else -> showShelf()
        }
    }
    override fun onDestroy() {
        super.onDestroy(); stopAudio(); speech.close(); worker.shutdownNow(); handler.removeCallbacksAndMessages(null)
        displayedBitmap = null
    }
}
