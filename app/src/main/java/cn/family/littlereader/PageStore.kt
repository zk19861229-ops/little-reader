package cn.family.littlereader

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class PageStore(private val root: File) {
    init { root.mkdirs() }
    fun newId(): String = UUID.randomUUID().toString()
    fun directory(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,64}"))) { "Invalid page ID" }
        return File(root, id)
    }
    fun image(id: String) = File(directory(id), "page.jpg")
    fun recording(id: String, sentence: ReadingSentence) = File(directory(id), "${sentence.audioKey}.m4a")

    @Synchronized fun save(page: ReadingPage) {
        val folder = directory(page.id).apply { mkdirs() }
        val json = JSONObject().put("id", page.id).put("title", page.title)
            .put("createdAt", page.createdAt).put("openedAt", page.openedAt)
        val sentences = JSONArray()
        page.sentences.forEach { sentence ->
            val boxes = JSONArray()
            sentence.boxes.forEach { b -> boxes.put(JSONArray(listOf(b.left, b.top, b.right, b.bottom))) }
            sentences.put(JSONObject().put("text", sentence.text).put("boxes", boxes))
        }
        json.put("sentences", sentences)
        val atomic = AtomicFile(File(folder, "page.json"))
        val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray()); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    @Synchronized fun get(id: String): ReadingPage? = runCatching {
        val json = JSONObject(String(AtomicFile(File(directory(id), "page.json")).readFully()))
        require(json.getString("id") == id)
        val rows = json.getJSONArray("sentences")
        ReadingPage(id, json.getString("title"), (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i); val bounds = row.getJSONArray("boxes")
            ReadingSentence(row.getString("text"), (0 until bounds.length()).map { n ->
                val b = bounds.getJSONArray(n); Box(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3))
            })
        }, json.getLong("createdAt"), json.optLong("openedAt", json.getLong("createdAt")))
    }.getOrNull()

    @Synchronized fun list(): List<ReadingPage> = root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.matches(Regex("[a-zA-Z0-9-]{1,64}")) }
        .mapNotNull { get(it.name) }.sortedByDescending { it.openedAt }

    @Synchronized fun delete(id: String) { directory(id).deleteRecursively() }
}
