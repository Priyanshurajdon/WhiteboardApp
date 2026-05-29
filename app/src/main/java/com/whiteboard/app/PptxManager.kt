package com.whiteboard.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import org.apache.poi.xslf.usermodel.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Manages loading and exporting PowerPoint (.pptx) files using Apache POI.
 * Renders slides to Bitmaps for display in the whiteboard.
 */
class PptxManager {

    private var presentation: XMLSlideShow? = null
    private var currentFile: File? = null

    val slideCount: Int get() = presentation?.slides?.size ?: 0
    val isLoaded: Boolean get() = presentation != null

    /**
     * Load a PPTX file and return list of slide bitmaps.
     * Rendering is done at the given target width/height.
     */
    fun loadPresentation(
        file: File,
        targetWidth: Int,
        targetHeight: Int,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<Bitmap> {
        try {
            FileInputStream(file).use { fis ->
                presentation = XMLSlideShow(fis)
            }
            currentFile = file
            return renderAllSlides(targetWidth, targetHeight, onProgress)
        } catch (e: Exception) {
            Log.e("PptxManager", "Error loading PPTX: ${e.message}", e)
            throw e
        }
    }

    /**
     * Render all slides to bitmaps.
     */
    fun renderAllSlides(
        targetWidth: Int,
        targetHeight: Int,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<Bitmap> {
        val pres = presentation ?: return emptyList()
        val bitmaps = mutableListOf<Bitmap>()

        val slideSize = pres.pageSize
        val slideAspect = slideSize.width.toFloat() / slideSize.height.toFloat()
        val canvasAspect = targetWidth.toFloat() / targetHeight.toFloat()

        val (renderW, renderH) = if (canvasAspect > slideAspect) {
            // Constrain by height
            val h = targetHeight
            val w = (h * slideAspect).toInt()
            Pair(w, h)
        } else {
            // Constrain by width
            val w = targetWidth
            val h = (w / slideAspect).toInt()
            Pair(w, h)
        }

        val slides = pres.slides
        slides.forEachIndexed { index, slide ->
            onProgress?.invoke(index + 1, slides.size)
            try {
                val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                // White background
                canvas.drawColor(Color.WHITE)

                // Use POI's built-in rendering
                val graphics = AndroidGraphics2D(canvas, renderW, renderH, slideSize.width, slideSize.height)
                slide.draw(graphics)

                bitmaps.add(bitmap)
            } catch (e: Exception) {
                Log.e("PptxManager", "Error rendering slide ${index + 1}: ${e.message}")
                // Add placeholder slide on error
                bitmaps.add(createErrorSlide(renderW, renderH, index + 1))
            }
        }
        return bitmaps
    }

    /**
     * Export the presentation with annotations baked in.
     * annotationBitmaps: map of slideIndex -> annotation bitmap (transparent background)
     */
    fun exportWithAnnotations(
        outputFile: File,
        annotationBitmaps: Map<Int, Bitmap>
    ): Boolean {
        val pres = presentation ?: return false
        try {
            val slides = pres.slides
            val slideSize = pres.pageSize

            annotationBitmaps.forEach { (slideIndex, annotBitmap) ->
                if (slideIndex >= slides.size) return@forEach
                val slide = slides[slideIndex]

                // Scale annotation bitmap to match slide dimensions
                val scaledAnnotation = Bitmap.createScaledBitmap(
                    annotBitmap,
                    slideSize.width,
                    slideSize.height,
                    true
                )

                // Save annotation as temp PNG
                val tempFile = File(outputFile.parent, "annot_temp_$slideIndex.png")
                FileOutputStream(tempFile).use { fos ->
                    scaledAnnotation.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }

                // Add as picture to the slide (on top)
                try {
                    val pictureData = pres.addPicture(tempFile, XSLFPictureData.PictureType.PNG)
                    val picture = slide.createPicture(pictureData)
                    picture.anchor = java.awt.geom.Rectangle2D.Double(
                        0.0, 0.0,
                        slideSize.width.toDouble(),
                        slideSize.height.toDouble()
                    )
                } catch (e: Exception) {
                    Log.e("PptxManager", "Failed to add annotation to slide: ${e.message}")
                }

                tempFile.delete()
                scaledAnnotation.recycle()
            }

            // Save to output file
            FileOutputStream(outputFile).use { fos ->
                pres.write(fos)
            }
            return true
        } catch (e: Exception) {
            Log.e("PptxManager", "Error exporting: ${e.message}", e)
            return false
        }
    }

    private fun createErrorSlide(width: Int, height: Int, slideNum: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 32f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Slide $slideNum", width / 2f, height / 2f, paint)
        paint.textSize = 20f
        canvas.drawText("(Render error — slide content may still be present)", width / 2f, height / 2f + 40f, paint)
        return bitmap
    }

    fun close() {
        presentation?.close()
        presentation = null
        currentFile = null
    }
}
