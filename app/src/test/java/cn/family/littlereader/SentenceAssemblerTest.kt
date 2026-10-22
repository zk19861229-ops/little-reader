package cn.family.littlereader

import org.junit.Assert.*
import org.junit.Test

class SentenceAssemblerTest {
    private val box = Box(10, 20, 100, 50)
    @Test fun mergesWrappedBookSentence() {
        val result = SentenceAssembler.assemble(listOf(
            RecognizedLine("We can see the bear", box, 0),
            RecognizedLine("go in the river!", box, 0),
            RecognizedLine("16", box, 1)
        ))
        assertEquals(listOf("We can see the bear go in the river!"), result.map { it.text })
        assertEquals(2, result.single().boxes.size)
    }
    @Test fun keepsDifferentParagraphsSeparate() {
        val result = SentenceAssembler.assemble(listOf(RecognizedLine("I like", box, 0), RecognizedLine("a dog", box, 1)))
        assertEquals(2, result.size)
    }
    @Test fun splitsSentencesAndSkipsChineseInstructions() {
        val result = SentenceAssembler.assemble(listOf(
            RecognizedLine("请跟读", box, 0), RecognizedLine("Hello! I am Sam.", box, 1)))
        assertEquals(listOf("Hello!", "I am Sam."), result.map { it.text })
    }
    @Test fun wordsPreserveContractionsAndSkipNumbers() {
        assertEquals(listOf("I'm", "a", "six-year-old", "boy"), ReadingSentence("I'm a six-year-old boy, 6!", listOf()).words)
    }
    @Test fun correctedSentenceUsesDifferentRecording() {
        assertNotEquals(ReadingSentence("a cat", listOf()).audioKey, ReadingSentence("a cap", listOf()).audioKey)
    }
}
