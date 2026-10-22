package cn.family.littlereader

import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PageStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun persistsAcrossInstancesAndOrdersByLastRead() {
        val root = temporary.newFolder(); val store = PageStore(root)
        val sentence = ReadingSentence("Hello!", listOf(Box(1, 2, 30, 40)))
        store.save(ReadingPage("first", "第一页", listOf(sentence), 1, 10))
        store.save(ReadingPage("second", "第二页", listOf(sentence), 2, 20))
        assertEquals(listOf("second", "first"), PageStore(root).list().map { it.id })
        assertEquals(sentence, PageStore(root).get("first")!!.sentences.single())
    }
    @Test fun corruptPageDoesNotHideHealthyPages() {
        val root = temporary.newFolder(); val store = PageStore(root)
        store.save(ReadingPage("healthy", "Page", listOf()))
        File(root, "broken").mkdirs(); File(root, "broken/page.json").writeText("{")
        assertEquals(listOf("healthy"), store.list().map { it.id })
    }
    @Test fun deletionRemovesPhotosAndRecordings() {
        val root = temporary.newFolder(); val store = PageStore(root)
        store.save(ReadingPage("page", "Page", listOf()))
        store.image("page").writeText("photo")
        store.recording("page", ReadingSentence("Hello", listOf())).writeText("audio")
        store.delete("page")
        assertFalse(store.directory("page").exists())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPathTraversal() {
        PageStore(temporary.newFolder()).directory("../outside")
    }
}
