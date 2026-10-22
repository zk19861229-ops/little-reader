package cn.family.littlereader

import java.security.MessageDigest

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class ReadingSentence(val text: String, val boxes: List<Box>) {
    val words: List<String> get() = Regex("[A-Za-z]+(?:['’][A-Za-z]+)*(?:-[A-Za-z]+)*").findAll(text).map { it.value }.toList()
    val audioKey: String get() = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)
}
data class ReadingPage(
    val id: String,
    val title: String,
    val sentences: List<ReadingSentence>,
    val createdAt: Long = System.currentTimeMillis(),
    val openedAt: Long = createdAt
)

data class RecognizedLine(val text: String, val box: Box, val block: Int)

/** Merge wrapped lines only inside one OCR paragraph. Never merge across columns/blocks. */
object SentenceAssembler {
    fun assemble(lines: List<RecognizedLine>): List<ReadingSentence> {
        val result = mutableListOf<ReadingSentence>()
        lines.groupBy { it.block }.values.forEach { block ->
            var text = ""
            var boxes = mutableListOf<Box>()
            fun flush() {
                if (text.isNotBlank()) result.add(ReadingSentence(text.trim(), boxes.toList()))
                text = ""; boxes = mutableListOf()
            }
            block.forEach lineLoop@ { line ->
                // Chinese instructions and page numbers should never become pronunciation examples.
                val english = line.text.replace(Regex("[^\\x20-\\x7E’“”]"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                if (!Regex("[A-Za-z]").containsMatchIn(english)) { flush(); return@lineLoop }
                val parts = english.split(Regex("(?<=[.!?])\\s+(?=[A-Za-z\"“])"))
                parts.forEach { part ->
                    if (text.isNotEmpty()) text += " "
                    text += part
                    boxes.add(line.box)
                    if (Regex("[.!?][\"”']?$").containsMatchIn(text)) flush()
                }
                // Keep unusually long OCR paragraphs usable and within TTS engine limits.
                if (text.length > 350) flush()
            }
            flush()
        }
        return result
    }
}
