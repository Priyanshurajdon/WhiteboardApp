package com.whiteboard.app

import android.graphics.*
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Composite
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.GraphicsConfiguration
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.Stroke
import java.awt.font.FontRenderContext
import java.awt.font.GlyphVector
import java.awt.geom.*
import java.awt.image.BufferedImage
import java.awt.image.BufferedImageOp
import java.awt.image.ImageObserver
import java.awt.image.RenderedImage
import java.awt.image.renderable.RenderableImage
import java.text.AttributedCharacterIterator

/**
 * Bridges Apache POI's Graphics2D rendering to Android Canvas.
 * This is a comprehensive implementation needed for PPTX slide rendering.
 */
class AndroidGraphics2D(
    private val canvas: Canvas,
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val sourceWidth: Int,
    private val sourceHeight: Int
) : java.awt.Graphics2D() {

    private val scaleX = targetWidth.toFloat() / sourceWidth
    private val scaleY = targetHeight.toFloat() / sourceHeight

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var currentFont: Font = Font("sans-serif", Font.PLAIN, 12)
    private var foreground: Color = Color.BLACK
    private var background: Color = Color.WHITE
    private var clip: Shape? = null
    private var transform = AffineTransform()
    private var stroke: Stroke = BasicStroke(1f)
    private var composite: Composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER)

    private val savedStates = ArrayDeque<GraphicsState>()

    data class GraphicsState(
        val font: Font,
        val foreground: Color,
        val background: Color,
        val transform: AffineTransform,
        val stroke: Stroke,
        val composite: Composite,
        val clipSave: Shape?
    )

    private fun applyColor(c: Color) {
        paint.color = android.graphics.Color.argb(c.alpha, c.red, c.green, c.blue)
    }

    private fun scaleX(x: Double) = (x * scaleX).toFloat()
    private fun scaleY(y: Double) = (y * scaleY).toFloat()
    private fun scaleX(x: Float) = x * scaleX
    private fun scaleY(y: Float) = y * scaleY
    private fun scaleX(x: Int) = x * scaleX
    private fun scaleY(y: Int) = y * scaleY

    private fun applyStrokePaint() {
        paint.reset()
        paint.isAntiAlias = true
        applyColor(foreground)
        paint.style = Paint.Style.STROKE
        if (stroke is BasicStroke) {
            val bs = stroke as BasicStroke
            paint.strokeWidth = bs.lineWidth * ((scaleX + scaleY) / 2f)
            paint.strokeCap = when (bs.endCap) {
                BasicStroke.CAP_ROUND -> Paint.Cap.ROUND
                BasicStroke.CAP_SQUARE -> Paint.Cap.SQUARE
                else -> Paint.Cap.BUTT
            }
            paint.strokeJoin = when (bs.lineJoin) {
                BasicStroke.JOIN_ROUND -> Paint.Join.ROUND
                BasicStroke.JOIN_BEVEL -> Paint.Join.BEVEL
                else -> Paint.Join.MITER
            }
        }
    }

    private fun applyFillPaint() {
        paint.reset()
        paint.isAntiAlias = true
        applyColor(foreground)
        paint.style = Paint.Style.FILL
    }

    // ===================== Core drawing =====================

    override fun draw(s: Shape) {
        applyStrokePaint()
        canvas.drawPath(shapeToPath(s), paint)
    }

    override fun fill(s: Shape) {
        applyFillPaint()
        canvas.drawPath(shapeToPath(s), paint)
    }

    override fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int) {
        applyStrokePaint()
        canvas.drawLine(scaleX(x1), scaleY(y1), scaleX(x2), scaleY(y2), paint)
    }

    override fun drawRect(x: Int, y: Int, width: Int, height: Int) {
        applyStrokePaint()
        canvas.drawRect(scaleX(x), scaleY(y), scaleX(x + width), scaleY(y + height), paint)
    }

    override fun fillRect(x: Int, y: Int, width: Int, height: Int) {
        applyFillPaint()
        canvas.drawRect(scaleX(x), scaleY(y), scaleX(x + width), scaleY(y + height), paint)
    }

    override fun clearRect(x: Int, y: Int, width: Int, height: Int) {
        val p = Paint().apply { color = android.graphics.Color.WHITE }
        canvas.drawRect(scaleX(x), scaleY(y), scaleX(x + width), scaleY(y + height), p)
    }

    override fun drawOval(x: Int, y: Int, width: Int, height: Int) {
        applyStrokePaint()
        canvas.drawOval(RectF(scaleX(x), scaleY(y), scaleX(x + width), scaleY(y + height)), paint)
    }

    override fun fillOval(x: Int, y: Int, width: Int, height: Int) {
        applyFillPaint()
        canvas.drawOval(RectF(scaleX(x), scaleY(y), scaleX(x + width), scaleY(y + height)), paint)
    }

    override fun drawRoundRect(x: Int, y: Int, width: Int, height: Int, arcWidth: Int, arcHeight: Int) {
        applyStrokePaint()
        canvas.drawRoundRect(
            RectF(scaleX(x), scaleY(y), scaleX(x + width), scaleY(y + height)),
            scaleX(arcWidth), scaleY(arcHeight), paint
        )
    }

    override fun fillRoundRect(x: Int, y: Int, width: Int, height: Int, arcWidth: Int, arcHeight: Int) {
        applyFillPaint()
        canvas.drawRoundRect(
            RectF(scaleX(x), scaleY(y), scaleX(x + width), scaleY(y + height)),
            scaleX(arcWidth), scaleY(arcHeight), paint
        )
    }

    override fun drawString(str: String, x: Int, y: Int) = drawString(str, x.toFloat(), y.toFloat())

    override fun drawString(str: String, x: Float, y: Float) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            applyColor(foreground)
            textSize = currentFont.size * ((scaleX + scaleY) / 2f)
            style = Paint.Style.FILL
            typeface = fontToTypeface(currentFont)
        }
        canvas.drawText(str, scaleX(x), scaleY(y), tp)
    }

    override fun drawString(iterator: AttributedCharacterIterator, x: Int, y: Int) {
        val sb = StringBuilder()
        var c = iterator.first()
        while (c != AttributedCharacterIterator.DONE) {
            sb.append(c)
            c = iterator.next()
        }
        drawString(sb.toString(), x, y)
    }

    override fun drawString(iterator: AttributedCharacterIterator, x: Float, y: Float) {
        val sb = StringBuilder()
        var c = iterator.first()
        while (c != AttributedCharacterIterator.DONE) {
            sb.append(c)
            c = iterator.next()
        }
        drawString(sb.toString(), x, y)
    }

    override fun drawImage(img: Image?, x: Int, y: Int, observer: ImageObserver?): Boolean {
        if (img == null) return false
        return try {
            val bmp = imageToBitmap(img) ?: return false
            canvas.drawBitmap(bmp, scaleX(x), scaleY(y), null)
            true
        } catch (e: Exception) { false }
    }

    override fun drawImage(img: Image?, x: Int, y: Int, width: Int, height: Int, observer: ImageObserver?): Boolean {
        if (img == null) return false
        return try {
            val bmp = imageToBitmap(img) ?: return false
            val dst = RectF(scaleX(x), scaleY(y), scaleX(x + width), scaleY(y + height))
            canvas.drawBitmap(bmp, null, dst, null)
            true
        } catch (e: Exception) { false }
    }

    override fun drawImage(img: Image?, x: Int, y: Int, bgcolor: Color?, observer: ImageObserver?) =
        drawImage(img, x, y, observer)

    override fun drawImage(img: Image?, x: Int, y: Int, width: Int, height: Int, bgcolor: Color?, observer: ImageObserver?) =
        drawImage(img, x, y, width, height, observer)

    override fun drawImage(img: Image?, dx1: Int, dy1: Int, dx2: Int, dy2: Int, sx1: Int, sy1: Int, sx2: Int, sy2: Int, observer: ImageObserver?): Boolean {
        if (img == null) return false
        return try {
            val bmp = imageToBitmap(img) ?: return false
            val src = android.graphics.Rect(sx1, sy1, sx2, sy2)
            val dst = RectF(scaleX(dx1), scaleY(dy1), scaleX(dx2), scaleY(dy2))
            canvas.drawBitmap(bmp, src, dst, null)
            true
        } catch (e: Exception) { false }
    }

    override fun drawImage(img: Image?, dx1: Int, dy1: Int, dx2: Int, dy2: Int, sx1: Int, sy1: Int, sx2: Int, sy2: Int, bgcolor: Color?, observer: ImageObserver?) =
        drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, observer)

    override fun drawRenderedImage(img: RenderedImage?, xform: AffineTransform?) {
        // Convert RenderedImage to BufferedImage if needed
        if (img is BufferedImage) {
            val bmp = bufferedImageToBitmap(img)
            canvas.drawBitmap(bmp, 0f, 0f, null)
        }
    }

    override fun drawRenderableImage(img: RenderableImage?, xform: AffineTransform?) {}

    override fun drawImage(img: BufferedImage?, op: BufferedImageOp?, x: Int, y: Int) {
        if (img == null) return
        val bmp = bufferedImageToBitmap(img)
        canvas.drawBitmap(bmp, scaleX(x), scaleY(y), null)
    }

    override fun drawPolygon(xPoints: IntArray, yPoints: IntArray, nPoints: Int) {
        applyStrokePaint()
        canvas.drawPath(pointsToPath(xPoints, yPoints, nPoints, true), paint)
    }

    override fun fillPolygon(xPoints: IntArray, yPoints: IntArray, nPoints: Int) {
        applyFillPaint()
        canvas.drawPath(pointsToPath(xPoints, yPoints, nPoints, true), paint)
    }

    override fun drawPolyline(xPoints: IntArray, yPoints: IntArray, nPoints: Int) {
        applyStrokePaint()
        canvas.drawPath(pointsToPath(xPoints, yPoints, nPoints, false), paint)
    }

    override fun drawArc(x: Int, y: Int, width: Int, height: Int, startAngle: Int, arcAngle: Int) {
        applyStrokePaint()
        val oval = RectF(scaleX(x), scaleY(y), scaleX(x + width), scaleY(y + height))
        canvas.drawArc(oval, (-startAngle).toFloat(), (-arcAngle).toFloat(), false, paint)
    }

    override fun fillArc(x: Int, y: Int, width: Int, height: Int, startAngle: Int, arcAngle: Int) {
        applyFillPaint()
        val oval = RectF(scaleX(x), scaleY(y), scaleX(x + width), scaleY(y + height))
        canvas.drawArc(oval, (-startAngle).toFloat(), (-arcAngle).toFloat(), true, paint)
    }

    // ===================== State management =====================

    override fun getFont() = currentFont
    override fun setFont(font: Font?) { if (font != null) currentFont = font }

    override fun getColor() = foreground
    override fun setColor(c: Color?) { if (c != null) foreground = c }

    override fun getPaint() = java.awt.Color(foreground.rgb)
    override fun setPaint(paint: java.awt.Paint?) {
        when (paint) {
            is Color -> foreground = paint
            is java.awt.GradientPaint -> foreground = paint.color1
            else -> {}
        }
    }

    override fun getBackground() = background
    override fun setBackground(color: Color?) { if (color != null) background = color }

    override fun getStroke() = stroke
    override fun setStroke(s: Stroke?) { if (s != null) stroke = s }

    override fun getComposite() = composite
    override fun setComposite(comp: Composite?) { if (comp != null) composite = comp }

    override fun getTransform(): AffineTransform = AffineTransform(transform)
    override fun setTransform(tx: AffineTransform?) { if (tx != null) transform = AffineTransform(tx) }
    override fun transform(tx: AffineTransform?) { tx?.let { transform.concatenate(it) } }
    override fun translate(x: Int, y: Int) { transform.translate(x.toDouble(), y.toDouble()) }
    override fun translate(tx: Double, ty: Double) { transform.translate(tx, ty) }
    override fun rotate(theta: Double) { transform.rotate(theta) }
    override fun rotate(theta: Double, x: Double, y: Double) { transform.rotate(theta, x, y) }
    override fun scale(sx: Double, sy: Double) { transform.scale(sx, sy) }
    override fun shear(shx: Double, shy: Double) { transform.shear(shx, shy) }

    override fun getClip() = clip
    override fun getClipBounds() = clip?.bounds
    override fun setClip(clip: Shape?) { this.clip = clip }
    override fun setClip(x: Int, y: Int, width: Int, height: Int) {
        this.clip = Rectangle2D.Double(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
    }
    override fun clipRect(x: Int, y: Int, width: Int, height: Int) = setClip(x, y, width, height)
    override fun clip(s: Shape?) { if (s != null) clip = s }

    override fun getRenderingHint(hintKey: RenderingHints.Key?) = null
    override fun getRenderingHints() = RenderingHints(mapOf())
    override fun setRenderingHint(hintKey: RenderingHints.Key?, hintValue: Any?) {}
    override fun setRenderingHints(hints: Map<*, *>?) {}
    override fun addRenderingHints(hints: Map<*, *>?) {}

    override fun getFontRenderContext() = FontRenderContext(null, true, true)
    override fun getFontMetrics(f: Font?) = getFontMetrics()
    override fun getFontMetrics(): FontMetrics {
        return object : FontMetrics(currentFont) {
            override fun getHeight() = (currentFont.size * 1.2).toInt()
            override fun getAscent() = currentFont.size
            override fun getDescent() = (currentFont.size * 0.2).toInt()
        }
    }

    override fun getDeviceConfiguration(): GraphicsConfiguration? = null

    override fun create(): Graphics = AndroidGraphics2D(canvas, targetWidth, targetHeight, sourceWidth, sourceHeight)
    override fun dispose() {}

    override fun copyArea(x: Int, y: Int, width: Int, height: Int, dx: Int, dy: Int) {}

    override fun drawGlyphVector(g: GlyphVector?, x: Float, y: Float) {}
    override fun hit(rect: java.awt.Rectangle?, s: Shape?, onStroke: Boolean) = false

    // ===================== Helpers =====================

    private fun fontToTypeface(font: Font): Typeface {
        val bold = font.isBold
        val italic = font.isItalic
        return when {
            bold && italic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            else -> Typeface.DEFAULT
        }
    }

    private fun shapeToPath(shape: Shape): Path {
        val path = Path()
        val pi = shape.getPathIterator(null)
        val coords = FloatArray(6)
        while (!pi.isDone) {
            when (pi.currentSegment(coords)) {
                java.awt.geom.PathIterator.SEG_MOVETO ->
                    path.moveTo(scaleX(coords[0].toDouble()), scaleY(coords[1].toDouble()))
                java.awt.geom.PathIterator.SEG_LINETO ->
                    path.lineTo(scaleX(coords[0].toDouble()), scaleY(coords[1].toDouble()))
                java.awt.geom.PathIterator.SEG_QUADTO ->
                    path.quadTo(
                        scaleX(coords[0].toDouble()), scaleY(coords[1].toDouble()),
                        scaleX(coords[2].toDouble()), scaleY(coords[3].toDouble())
                    )
                java.awt.geom.PathIterator.SEG_CUBICTO ->
                    path.cubicTo(
                        scaleX(coords[0].toDouble()), scaleY(coords[1].toDouble()),
                        scaleX(coords[2].toDouble()), scaleY(coords[3].toDouble()),
                        scaleX(coords[4].toDouble()), scaleY(coords[5].toDouble())
                    )
                java.awt.geom.PathIterator.SEG_CLOSE -> path.close()
            }
            pi.next()
        }
        return path
    }

    private fun pointsToPath(xPoints: IntArray, yPoints: IntArray, nPoints: Int, close: Boolean): Path {
        val path = Path()
        if (nPoints == 0) return path
        path.moveTo(scaleX(xPoints[0]), scaleY(yPoints[0]))
        for (i in 1 until nPoints) {
            path.lineTo(scaleX(xPoints[i]), scaleY(yPoints[i]))
        }
        if (close) path.close()
        return path
    }

    private fun imageToBitmap(img: Image): Bitmap? {
        if (img is BufferedImage) return bufferedImageToBitmap(img)
        return null
    }

    private fun bufferedImageToBitmap(img: BufferedImage): Bitmap {
        val bmp = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                bmp.setPixel(x, y, img.getRGB(x, y))
            }
        }
        return bmp
    }
}
