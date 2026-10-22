package cn.family.littlereader

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.Manifest
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.content.Intent
import android.app.Activity
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReadingFlowTest {
    @Test fun pickerResultIsRecognizedSavedAndDisplayed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = PageStore(File(context.filesDir, "pages"))
        val before = store.list().map { it.id }.toSet() + "demo"
        val fixture = File(context.cacheDir, "picked-photo.webp")
        context.assets.open("demo.webp").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val callback = MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
                    callback.isAccessible = true
                    callback.invoke(activity, 102, Activity.RESULT_OK, Intent().setData(Uri.fromFile(fixture)))
                }
                val deadline = SystemClock.elapsedRealtime() + 15000
                while (store.list().none { it.id !in before } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                instrumentation.waitForIdleSync()
                val imported = store.list().firstOrNull { it.id !in before }
                assertNotNull("Photo should become a saved reading page", imported)
                assertTrue(imported!!.sentences.joinToString(" ") { it.text }.contains("river", ignoreCase = true))
                assertTrue(store.image(imported.id).length() > 0)
                scenario.onActivity { activity ->
                    assertTrue(texts(activity.window.decorView).any { it.text.toString().contains("听示范") })
                }
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.cacheDir, "imported-page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        } finally {
            store.list().filter { it.id !in before }.forEach { store.delete(it.id) }
            fixture.delete()
        }
    }

    @Test fun bundledRecognizerReadsActualBookPhotoWithoutNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = context.assets.open("demo.webp").use { BitmapFactory.decodeStream(it) }
        try {
            val result = BookRecognizer().recognize(bitmap)
            val all = result.joinToString(" ") { it.text }.lowercase()
            assertTrue("OCR result: $all", all.contains("we can see the bear"))
            assertTrue("OCR result: $all", all.contains("go in the river"))
            assertTrue(result.all { it.boxes.isNotEmpty() })
        } finally { bitmap.recycle() }
    }

    @Test fun opensDemoSelectsWordsAndSurvivesActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val open = texts(activity.window.decorView).first { it.text.toString().contains("打开点读") }
                open.performClick()
                assertTrue(texts(activity.window.decorView).any { it.text.toString() == "river" })
                texts(activity.window.decorView).first { it.text.toString() == "river" }.performClick()
                assertTrue(texts(activity.window.decorView).any { it.text.toString().contains("跟我读") })
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(texts(activity.window.decorView).any { it.text.toString() == "We can see the bear go in the river!" })
                assertTrue(texts(activity.window.decorView).any { it.text.toString() == "慢一点" })
            }
        }
    }

    @Test fun pageStoreSurvivesReopeningAndDeletesAllAssociatedFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "store-instrumentation").apply { deleteRecursively(); mkdirs() }
        try {
            val page = ReadingPage("test-page", "Test", listOf(ReadingSentence("Hello!", listOf(Box(1, 2, 100, 50)))))
            PageStore(root).save(page)
            assertEquals(page, PageStore(root).get(page.id))
            PageStore(root).recording(page.id, page.sentences.single()).writeText("recording")
            PageStore(root).delete(page.id)
            assertTrue(PageStore(root).list().isEmpty())
            assertFalse(File(root, page.id).exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun photoOrientationIsNormalizedBeforeRecognition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "rotated-photo.jpg")
        val destination = File(context.cacheDir, "normalized-photo.jpg")
        val original = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        source.outputStream().use { original.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        original.recycle()
        ExifInterface(source).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
        val normalized = PhotoLoader.import(context, Uri.fromFile(source), destination)
        try { assertEquals(200, normalized.width); assertEquals(400, normalized.height) }
        finally { normalized.recycle(); source.delete(); destination.delete() }
    }

    @Test fun microphoneTakeIsSavedAsPlayableAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                texts(activity.window.decorView).first { it.text.toString().contains("打开点读") }.performClick()
                texts(activity.window.decorView).first { it.text.toString().contains("跟我读") }.performClick()
                assertTrue(texts(activity.window.decorView).any { it.text.toString().contains("读完了") })
            }
            Thread.sleep(1100)
            scenario.onActivity { activity ->
                texts(activity.window.decorView).first { it.text.toString().contains("读完了") }.performClick()
                val compare = texts(activity.window.decorView).first { it.text.toString() == "听听我读的" }
                assertTrue(compare.isEnabled)
                val store = PageStore(File(context.filesDir, "pages"))
                val demo = store.get("demo")!!
                val file = store.recording(demo.id, demo.sentences.single())
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(file.absolutePath)
                    assertTrue((reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0) > 500)
                } finally { reader.release() }
            }
        }
    }

    private fun texts(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(texts(view.getChildAt(i)))
    }
}
