package cn.family.littlereader

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/** Bundled Latin model: does not need Google Play Services or a first-run model download. */
class BookRecognizer {
    fun recognize(bitmap: Bitmap): List<ReadingSentence> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 45, TimeUnit.SECONDS)
            val lines = result.textBlocks.flatMapIndexed { index, block -> block.lines.mapNotNull { line ->
                line.boundingBox?.let { rect -> RecognizedLine(line.text, Box(rect.left, rect.top, rect.right, rect.bottom), index) }
            } }
            return SentenceAssembler.assemble(lines)
        } finally { recognizer.close() }
    }
}
