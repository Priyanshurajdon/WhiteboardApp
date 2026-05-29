package com.whiteboard.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.whiteboard.app.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val pptxManager = PptxManager()

    // Slide state
    private val slideBitmaps = mutableListOf<Bitmap>()
    private var currentSlideIndex = 0
    private var totalSlides = 0

    // Coroutine scope for background ops
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Request codes
    companion object {
        const val REQUEST_IMPORT_PPT = 1001
        const val REQUEST_PERMISSION = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupDrawingToolbar()
        updateSlideUI()
    }

    // ===================== Toolbar Setup =====================

    private fun setupToolbar() {
        binding.btnImportPpt.setOnClickListener { checkPermissionsAndImport() }
        binding.btnPrevSlide.setOnClickListener { navigateSlide(-1) }
        binding.btnNextSlide.setOnClickListener { navigateSlide(1) }
        binding.btnExportPpt.setOnClickListener { exportPresentation() }
        binding.btnClearDrawing.setOnClickListener { confirmClearDrawing() }
        binding.btnUndo.setOnClickListener { binding.drawingView.undo() }

        // Color picker toggle
        binding.btnColorPicker.setOnClickListener {
            binding.colorPalette.visibility =
                if (binding.colorPalette.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // Color swatches
        setupColorSwatch(binding.colorRed, 0xFFE94560.toInt())
        setupColorSwatch(binding.colorBlue, 0xFF0F3460.toInt())
        setupColorSwatch(binding.colorGreen, 0xFF00B4D8.toInt())
        setupColorSwatch(binding.colorYellow, 0xFFFFD700.toInt())
        setupColorSwatch(binding.colorOrange, 0xFFFF6B35.toInt())
        setupColorSwatch(binding.colorPurple, 0xFF7B2FBE.toInt())
        setupColorSwatch(binding.colorWhite, 0xFFFFFFFF.toInt())
        setupColorSwatch(binding.colorBlack, 0xFF000000.toInt())
    }

    private fun setupColorSwatch(view: View, color: Int) {
        view.setOnClickListener {
            binding.drawingView.strokeColor = color
            binding.btnColorPicker.setBackgroundColor(color)
            binding.colorPalette.visibility = View.GONE
        }
    }

    private fun setupDrawingToolbar() {
        // Tool buttons
        binding.btnPen.setOnClickListener { selectTool(DrawingView.Tool.PEN) }
        binding.btnHighlighter.setOnClickListener { selectTool(DrawingView.Tool.HIGHLIGHTER) }
        binding.btnEraser.setOnClickListener { selectTool(DrawingView.Tool.ERASER) }
        binding.btnLine.setOnClickListener { selectTool(DrawingView.Tool.LINE) }
        binding.btnRect.setOnClickListener { selectTool(DrawingView.Tool.RECTANGLE) }
        binding.btnCircle.setOnClickListener { selectTool(DrawingView.Tool.CIRCLE) }

        // Stroke sizes
        binding.btnSizeSmall.setOnClickListener { setStrokeSize(4f, binding.btnSizeSmall) }
        binding.btnSizeMedium.setOnClickListener { setStrokeSize(8f, binding.btnSizeMedium) }
        binding.btnSizeLarge.setOnClickListener { setStrokeSize(16f, binding.btnSizeLarge) }

        // Default tool
        selectTool(DrawingView.Tool.PEN)
        setStrokeSize(6f, binding.btnSizeMedium)

        // Callback when drawing changes
        binding.drawingView.onDrawingChanged = {
            // Could update save-state indicator here
        }
    }

    private fun selectTool(tool: DrawingView.Tool) {
        binding.drawingView.currentTool = tool

        // Update button appearance
        val toolButtons = listOf(
            binding.btnPen to DrawingView.Tool.PEN,
            binding.btnHighlighter to DrawingView.Tool.HIGHLIGHTER,
            binding.btnEraser to DrawingView.Tool.ERASER,
            binding.btnLine to DrawingView.Tool.LINE,
            binding.btnRect to DrawingView.Tool.RECTANGLE,
            binding.btnCircle to DrawingView.Tool.CIRCLE
        )

        toolButtons.forEach { (btn, t) ->
            btn.setBackgroundColor(
                if (t == tool) getColor(android.R.color.holo_red_dark)
                else 0xFF0F3460.toInt()
            )
        }
    }

    private fun setStrokeSize(size: Float, activeBtn: com.google.android.material.button.MaterialButton) {
        binding.drawingView.strokeWidth = size
        listOf(binding.btnSizeSmall, binding.btnSizeMedium, binding.btnSizeLarge).forEach { btn ->
            btn.setBackgroundColor(
                if (btn == activeBtn) 0xFFE94560.toInt()
                else 0xFF0F3460.toInt()
            )
        }
    }

    // ===================== Slide Navigation =====================

    private fun navigateSlide(delta: Int) {
        val newIndex = currentSlideIndex + delta
        if (newIndex < 0 || newIndex >= totalSlides) return

        currentSlideIndex = newIndex
        showCurrentSlide()
    }

    private fun showCurrentSlide() {
        if (slideBitmaps.isEmpty()) return

        val bitmap = slideBitmaps.getOrNull(currentSlideIndex) ?: return
        binding.slideImageView.setImageBitmap(bitmap)
        binding.drawingView.setCurrentSlide(currentSlideIndex)
        updateSlideUI()
    }

    private fun updateSlideUI() {
        if (totalSlides == 0) {
            binding.tvSlideCounter.text = "No Slides"
            binding.btnPrevSlide.isEnabled = false
            binding.btnNextSlide.isEnabled = false
            binding.btnExportPpt.isEnabled = false
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.tvSlideCounter.text = "${currentSlideIndex + 1} / $totalSlides"
            binding.btnPrevSlide.isEnabled = currentSlideIndex > 0
            binding.btnNextSlide.isEnabled = currentSlideIndex < totalSlides - 1
            binding.btnExportPpt.isEnabled = true
            binding.emptyState.visibility = View.GONE
        }
    }

    // ===================== Import PPT =====================

    private fun checkPermissionsAndImport() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: READ_MEDIA_IMAGES covers what we need; file picker handles the rest
            openFilePicker()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val perm = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
                openFilePicker()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(perm), REQUEST_PERMISSION)
            }
        } else {
            openFilePicker()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openFilePicker()
            } else {
                Toast.makeText(this, "Permission required to import files", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.ms-powerpoint"
            ))
        }
        startActivityForResult(intent, REQUEST_IMPORT_PPT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT_PPT && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri -> importPptx(uri) }
        }
    }

    private fun importPptx(uri: Uri) {
        val progress = ProgressDialog(this).apply {
            setTitle("Importing Presentation")
            setMessage("Loading slide 1...")
            isIndeterminate = false
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            show()
        }

        scope.launch {
            try {
                // Copy URI to cache file
                val tempFile = withContext(Dispatchers.IO) {
                    copyUriToTemp(uri)
                }

                if (tempFile == null) {
                    withContext(Dispatchers.Main) {
                        progress.dismiss()
                        Toast.makeText(this@MainActivity, "Failed to read file", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Get canvas dimensions
                val canvasWidth = binding.canvasContainer.width.takeIf { it > 0 } ?: 1280
                val canvasHeight = binding.canvasContainer.height.takeIf { it > 0 } ?: 720

                // Load and render slides
                val bitmaps = withContext(Dispatchers.IO) {
                    pptxManager.loadPresentation(tempFile, canvasWidth, canvasHeight) { current, total ->
                        runOnUiThread {
                            progress.max = total
                            progress.progress = current
                            progress.setMessage("Rendering slide $current of $total...")
                        }
                    }
                }

                // Update UI
                slideBitmaps.clear()
                slideBitmaps.addAll(bitmaps)
                totalSlides = bitmaps.size
                currentSlideIndex = 0

                progress.dismiss()

                if (bitmaps.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No slides found in presentation", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                showCurrentSlide()
                Toast.makeText(
                    this@MainActivity,
                    "Loaded $totalSlides slide${if (totalSlides != 1) "s" else ""}",
                    Toast.LENGTH_SHORT
                ).show()

                tempFile.delete()

            } catch (e: Exception) {
                progress.dismiss()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Import Error")
                    .setMessage("Could not load the presentation:\n${e.message}\n\nMake sure the file is a valid .pptx file.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun copyUriToTemp(uri: Uri): File? {
        return try {
            val fileName = getFileName(uri) ?: "presentation.pptx"
            val tempFile = File(cacheDir, "import_$fileName")

            contentResolver.openInputStream(uri)?.use { input: InputStream ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) result = cursor.getString(idx)
                }
            }
        }
        if (result == null) {
            result = uri.path?.substringAfterLast('/')
        }
        return result
    }

    // ===================== Export PPT =====================

    private fun exportPresentation() {
        if (!pptxManager.isLoaded) {
            Toast.makeText(this, "No presentation loaded", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Export Presentation")
            .setMessage("Save annotated presentation?\n\nYour drawings will be baked into the slides.")
            .setPositiveButton("Export") { _, _ -> performExport() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performExport() {
        val progress = ProgressDialog(this).apply {
            setTitle("Exporting")
            setMessage("Saving annotated presentation...")
            isIndeterminate = true
            setCancelable(false)
            show()
        }

        scope.launch {
            try {
                val annotations = binding.drawingView.getAllAnnotations()

                val outputFile = withContext(Dispatchers.IO) {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                        ?: filesDir
                    val timestamp = System.currentTimeMillis()
                    val file = File(dir, "annotated_$timestamp.pptx")
                    pptxManager.exportWithAnnotations(file, annotations)
                    file
                }

                progress.dismiss()

                // Share the file
                val fileUri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.fileprovider",
                    outputFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Export Complete")
                    .setMessage("Saved to:\n${outputFile.absolutePath}")
                    .setPositiveButton("Share") { _, _ ->
                        startActivity(Intent.createChooser(shareIntent, "Share Presentation"))
                    }
                    .setNegativeButton("OK", null)
                    .show()

            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    "Export failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ===================== Utilities =====================

    private fun confirmClearDrawing() {
        AlertDialog.Builder(this)
            .setTitle("Clear Drawing")
            .setMessage("Remove all drawings from the current slide?")
            .setPositiveButton("Clear") { _, _ -> binding.drawingView.clearCurrentSlide() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        pptxManager.close()
        slideBitmaps.forEach { it.recycle() }
        slideBitmaps.clear()
    }
}
