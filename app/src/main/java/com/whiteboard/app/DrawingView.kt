package com.whiteboard.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Custom drawing canvas that supports multiple drawing tools,
 * per-slide annotation layers, undo, eraser, shapes, etc.
 */
class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Tool {
        PEN, HIGHLIGHTER, ERASER, LINE, RECTANGLE, CIRCLE
    }

    data class DrawAction(
        val path: Path,
        val paint: Paint,
        val tool: Tool,
        // For shape tools: start/end coordinates
        val startX: Float = 0f,
        val startY: Float = 0f,
        val endX: Float = 0f,
        val endY: Float = 0f
    )

    // Current stroke settings
    var currentTool: Tool = Tool.PEN
    var strokeColor: Int = Color.RED
    var strokeWidth: Float = 6f
    var highlighterAlpha: Int = 80 // semi-transparent

    // Per-slide drawing layers: slideIndex -> list of actions
    private val slideAnnotations = mutableMapOf<Int, MutableList<DrawAction>>()
    private var currentSlideIndex: Int = -1

    // For free-draw and shape tools
    private var currentPath = Path()
    private var shapeStartX = 0f
    private var shapeStartY = 0f
    private var shapeEndX = 0f
    private var shapeEndY = 0f
    private var isDrawingShape = false

    // Offscreen bitmap for committed strokes (performance)
    private var canvasBitmap: Bitmap? = null
    private var offscreenCanvas: Canvas? = null

    // Callbacks
    var onDrawingChanged: (() -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        offscreenCanvas = Canvas(canvasBitmap!!)
        redrawCurrentSlide()
    }

    /** Switch to a new slide — loads its annotation layer */
    fun setCurrentSlide(index: Int) {
        currentSlideIndex = index
        if (!slideAnnotations.containsKey(index)) {
            slideAnnotations[index] = mutableListOf()
        }
        redrawCurrentSlide()
    }

    /** Undo last stroke on current slide */
    fun undo() {
        val actions = slideAnnotations[currentSlideIndex] ?: return
        if (actions.isNotEmpty()) {
            actions.removeAt(actions.size - 1)
            redrawCurrentSlide()
            onDrawingChanged?.invoke()
        }
    }

    /** Clear all drawings on current slide */
    fun clearCurrentSlide() {
        slideAnnotations[currentSlideIndex]?.clear()
        redrawCurrentSlide()
        onDrawingChanged?.invoke()
    }

    /** Returns all annotation bitmaps keyed by slide index */
    fun getAllAnnotations(): Map<Int, Bitmap> {
        val result = mutableMapOf<Int, Bitmap>()
        val savedSlide = currentSlideIndex
        for ((index, actions) in slideAnnotations) {
            if (actions.isEmpty()) continue
            val bmp = Bitmap.createBitmap(
                canvasBitmap?.width ?: width,
                canvasBitmap?.height ?: height,
                Bitmap.Config.ARGB_8888
            )
            val c = Canvas(bmp)
            drawActions(c, actions)
            result[index] = bmp
        }
        currentSlideIndex = savedSlide
        return result
    }

    /** Get annotation bitmap for a specific slide */
    fun getAnnotationBitmap(slideIndex: Int): Bitmap? {
        val actions = slideAnnotations[slideIndex]
        if (actions.isNullOrEmpty()) return null
        val bmp = Bitmap.createBitmap(
            canvasBitmap?.width ?: width,
            canvasBitmap?.height ?: height,
            Bitmap.Config.ARGB_8888
        )
        val c = Canvas(bmp)
        drawActions(c, actions)
        return bmp
    }

    private fun redrawCurrentSlide() {
        val c = offscreenCanvas ?: return
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val actions = slideAnnotations[currentSlideIndex] ?: return
        drawActions(c, actions)
        invalidate()
    }

    private fun drawActions(canvas: Canvas, actions: List<DrawAction>) {
        for (action in actions) {
            when (action.tool) {
                Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER -> {
                    canvas.drawPath(action.path, action.paint)
                }
                Tool.LINE -> {
                    canvas.drawLine(action.startX, action.startY, action.endX, action.endY, action.paint)
                }
                Tool.RECTANGLE -> {
                    val rect = RectF(
                        minOf(action.startX, action.endX),
                        minOf(action.startY, action.endY),
                        maxOf(action.startX, action.endX),
                        maxOf(action.startY, action.endY)
                    )
                    canvas.drawRect(rect, action.paint)
                }
                Tool.CIRCLE -> {
                    val cx = (action.startX + action.endX) / 2f
                    val cy = (action.startY + action.endY) / 2f
                    val rx = abs(action.endX - action.startX) / 2f
                    val ry = abs(action.endY - action.startY) / 2f
                    canvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), action.paint)
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw committed strokes
        canvasBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // Draw current in-progress stroke/shape
        when (currentTool) {
            Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER -> {
                canvas.drawPath(currentPath, makePaint())
            }
            Tool.LINE -> {
                if (isDrawingShape) {
                    canvas.drawLine(shapeStartX, shapeStartY, shapeEndX, shapeEndY, makePaint())
                }
            }
            Tool.RECTANGLE -> {
                if (isDrawingShape) {
                    val rect = RectF(
                        minOf(shapeStartX, shapeEndX),
                        minOf(shapeStartY, shapeEndY),
                        maxOf(shapeStartX, shapeEndX),
                        maxOf(shapeStartY, shapeEndY)
                    )
                    canvas.drawRect(rect, makePaint())
                }
            }
            Tool.CIRCLE -> {
                if (isDrawingShape) {
                    val cx = (shapeStartX + shapeEndX) / 2f
                    val cy = (shapeStartY + shapeEndY) / 2f
                    val rx = abs(shapeEndX - shapeStartX) / 2f
                    val ry = abs(shapeEndY - shapeStartY) / 2f
                    canvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), makePaint())
                }
            }
        }
    }

    private fun makePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        when (currentTool) {
            Tool.PEN -> {
                color = strokeColor
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.strokeWidth = this@DrawingView.strokeWidth
                xfermode = null
            }
            Tool.HIGHLIGHTER -> {
                color = strokeColor
                alpha = highlighterAlpha
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.SQUARE
                this.strokeWidth = this@DrawingView.strokeWidth * 4f
                xfermode = null
            }
            Tool.ERASER -> {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                this.strokeWidth = this@DrawingView.strokeWidth * 5f
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            Tool.LINE, Tool.RECTANGLE -> {
                color = strokeColor
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                this.strokeWidth = this@DrawingView.strokeWidth
                xfermode = null
            }
            Tool.CIRCLE -> {
                color = strokeColor
                style = Paint.Style.STROKE
                this.strokeWidth = this@DrawingView.strokeWidth
                xfermode = null
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (currentSlideIndex < 0 && currentSlideIndex != -1) return false

        val x = event.x
        val y = event.y

        when (currentTool) {
            Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER -> handleFreeDraw(event, x, y)
            Tool.LINE, Tool.RECTANGLE, Tool.CIRCLE -> handleShapeDraw(event, x, y)
        }
        return true
    }

    private fun handleFreeDraw(event: MotionEvent, x: Float, y: Float) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = Path()
                currentPath.moveTo(x, y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath.lineTo(x, y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                currentPath.lineTo(x, y)
                commitAction(DrawAction(
                    path = Path(currentPath),
                    paint = makePaint(),
                    tool = currentTool
                ))
                currentPath.reset()
                invalidate()
            }
        }
    }

    private fun handleShapeDraw(event: MotionEvent, x: Float, y: Float) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                shapeStartX = x
                shapeStartY = y
                shapeEndX = x
                shapeEndY = y
                isDrawingShape = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                shapeEndX = x
                shapeEndY = y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                shapeEndX = x
                shapeEndY = y
                isDrawingShape = false
                commitAction(DrawAction(
                    path = Path(),
                    paint = makePaint(),
                    tool = currentTool,
                    startX = shapeStartX,
                    startY = shapeStartY,
                    endX = shapeEndX,
                    endY = shapeEndY
                ))
                invalidate()
            }
        }
    }

    private fun commitAction(action: DrawAction) {
        // Ensure slide exists
        if (currentSlideIndex == -1) {
            currentSlideIndex = 0
        }
        if (!slideAnnotations.containsKey(currentSlideIndex)) {
            slideAnnotations[currentSlideIndex] = mutableListOf()
        }
        slideAnnotations[currentSlideIndex]!!.add(action)
        // Redraw to offscreen canvas
        offscreenCanvas?.let { drawActions(it, listOf(action)) }
        onDrawingChanged?.invoke()
    }
}
