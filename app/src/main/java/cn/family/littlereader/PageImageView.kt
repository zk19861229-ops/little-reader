package cn.family.littlereader

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

class PageImageView(context: Context, private val bitmap: Bitmap, private val sentences: List<ReadingSentence>) : View(context) {
    var selected = 0
        set(value) { field = value; invalidate() }
    var onSentence: ((Int) -> Unit)? = null
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var scale = 1f
    private var leftOffset = 0f
    private var topOffset = 0f
    init { contentDescription = "书页图片；也可以点击下方的大号句子朗读" }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        leftOffset = (width - bitmap.width * scale) / 2f
        topOffset = (height - bitmap.height * scale) / 2f
        canvas.save(); canvas.translate(leftOffset, topOffset); canvas.scale(scale, scale)
        pen.style = Paint.Style.FILL; pen.alpha = 255
        canvas.drawBitmap(bitmap, 0f, 0f, pen)
        sentences.forEachIndexed { index, sentence -> sentence.boxes.forEach { b ->
            rect.set(b.left - 5f, b.top - 5f, b.right + 5f, b.bottom + 5f)
            pen.color = if (index == selected) Color.rgb(40, 120, 93) else Color.rgb(211, 141, 32)
            pen.alpha = if (index == selected) 40 else 12; pen.style = Paint.Style.FILL
            canvas.drawRoundRect(rect, 7f, 7f, pen)
            pen.alpha = 230; pen.style = Paint.Style.STROKE; pen.strokeWidth = 2f / scale
            canvas.drawRoundRect(rect, 7f, 7f, pen)
        } }
        pen.style = Paint.Style.FILL; pen.alpha = 255; canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = (event.x - leftOffset) / scale; val y = (event.y - topOffset) / scale
            val margin = 10 * resources.displayMetrics.density / scale
            val match = sentences.indexOfFirst { s -> s.boxes.any { b ->
                x >= b.left - margin && x <= b.right + margin && y >= b.top - margin && y <= b.bottom + margin
            } }
            if (match >= 0) { selected = match; onSentence?.invoke(match); performClick() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
