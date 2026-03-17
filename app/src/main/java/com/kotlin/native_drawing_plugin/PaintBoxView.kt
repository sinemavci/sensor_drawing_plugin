package com.kotlin.native_drawing_plugin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import android.graphics.Color
import android.media.ExifInterface
import kotlin.collections.mutableListOf
import kotlin.math.abs
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColor
import com.kotlin.native_drawing_plugin.export_util.ExportUtil
import com.kotlin.native_drawing_plugin.tool.IPaintTool
import com.kotlin.native_drawing_plugin.tool.PaintToolFactory
import com.kotlin.native_drawing_plugin.tool.PenTool
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.scale

sealed class DrawAction {
    data class StrokeAction(val path: Path, val paint: Paint) : DrawAction()
    data class ImageAction(val bitmap: Bitmap, val matrix: Matrix) : DrawAction()
}

class PaintBoxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {
    val paintEditor = PaintEditor(paintBoxView = this)
    private var tool: IPaintTool = PenTool()
    private var exportUtil = ExportUtil()
    private val gifFrames = mutableListOf<Bitmap>()

    private var isPaintBoxViewEnable = true

    private var strokeColor: Int = Color.BLACK

    private var topInsetPx = 0

    // Default pai
    private val paintDefaults = Paint().apply {
        isAntiAlias = true
        isDither = true
        color = strokeColor
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 40f
    }

    private data class Stroke(val path: Path, val paint: Paint)

    private val actions = mutableListOf<DrawAction>()
    private val undoneActions = mutableListOf<DrawAction>()

    // Current drawing path
    private var currentPath = Path()
    private var currentPaint = Paint(paintDefaults)

    // Offscreen cache
    private var extraBitmap: Bitmap? = null
    private var extraCanvas: Canvas? = null

    // Touch smoothing
    private var lastX = 0f
    private var lastY = 0f
    private val touchTolerance = 4f

    init {
        holder.addCallback(this)
        setOnApplyWindowInsetsListener { _, insets ->
            topInsetPx = insets.systemWindowInsetTop
            insets
        }
    }

    var surfaceWidth = width
    var surfaceHeight = height

    // -------------------------------------------------------------------------
    // Surface lifecycle
    // -------------------------------------------------------------------------
    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceWidth = width
        surfaceHeight = height
        // Initialize offscreen bitmap based on actual surface size

        if (surfaceWidth > 0 && surfaceHeight > 0) {
            extraBitmap = Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ARGB_8888)
            extraCanvas = Canvas(extraBitmap!!)
            extraCanvas?.drawColor(Color.WHITE)
        }

        redrawSurface()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        extraBitmap?.recycle()
        extraBitmap = null
        extraCanvas = null
    }

    // -------------------------------------------------------------------------
    // TOUCH HANDLING
    // -------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!isPaintBoxViewEnable) return false
        val pointerIndex = event.actionIndex
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        if (y < topInsetPx) return false

        val pressure = event.getPressure(pointerIndex)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startStroke(x, y, pressure)
                redrawSurface()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                moveStroke(x, y)
                redrawSurface()
                return true
            }

            MotionEvent.ACTION_UP -> {
                endStroke()
                redrawSurface()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // -------------------------------------------------------------------------
    // STROKE HELPERS
    // -------------------------------------------------------------------------

    private fun startStroke(x: Float, y: Float, pressure: Float) {
        currentPath = Path()
        currentPath.moveTo(x, y)
        currentPaint.strokeWidth = currentPaint.strokeWidth * pressure

        lastX = x
        lastY = y
    }

    private fun moveStroke(x: Float, y: Float) {
        val dx = abs(x - lastX)
        val dy = abs(y - lastY)

        if (dx >= touchTolerance || dy >= touchTolerance) {
            currentPath.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
            lastX = x
            lastY = y
        }
    }

    private fun endStroke() {
        // Save stroke
        val savedPath = Path(currentPath)
        val savedPaint = Paint(currentPaint)
        actions.add(
            DrawAction.StrokeAction(savedPath, savedPaint)
        )

        // Draw to cached bitmap
        extraCanvas?.drawPath(savedPath, savedPaint)
        extraBitmap?.let {
            gifFrames.add(it.copy(it.config!!, false))
        }
        currentPath.reset()
    }

    // -------------------------------------------------------------------------
    // DRAWING TO SURFACE
    // -------------------------------------------------------------------------

    private fun redrawSurface() {
        val canvas = holder.lockCanvas() ?: return

        // Clear surface
        canvas.drawColor(Color.WHITE)

        canvas.clipRect(0, topInsetPx, width, height)

        // Draw cached strokes
        extraBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }

        // Draw current active stroke
        canvas.drawPath(currentPath, currentPaint)

        holder.unlockCanvasAndPost(canvas)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    internal fun undo() {
        if (actions.isNotEmpty()) {
            undoneActions.add(actions.removeLast())
            redrawCachedBitmap()
            redrawSurface()
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    internal fun redo() {
        if (undoneActions.isNotEmpty()) {
            actions.add(undoneActions.removeLast())
            redrawCachedBitmap()
            redrawSurface()
        }
    }

    internal fun reset() {
        if (actions.isNotEmpty()) {
            actions.clear()
            undoneActions.clear()
            redrawCachedBitmap()
            redrawSurface()
        }
    }

    private fun redrawCachedBitmap() {
        extraCanvas?.drawColor(Color.WHITE)
        for (action in actions) {
            when (action) {
                is DrawAction.ImageAction -> {
                    extraCanvas?.drawBitmap(
                        action.bitmap,
                        action.matrix,
                        null
                    )
                }
                is DrawAction.StrokeAction -> {
                    extraCanvas?.drawPath(
                        action.path,
                        action.paint
                    )
                }
            }
        }
    }

    internal fun setStrokeColor(color: Color) {
        strokeColor = color.toArgb()
        currentPaint.setColor(strokeColor)
    }

    internal fun getStrokeColor(): Color {
        return strokeColor.toColor()
    }

    internal fun setStrokeWidth(widthPx: Float) {
        currentPaint.strokeWidth = widthPx
        currentPaint.apply {
            strokeWidth = widthPx
        }
    }

    internal fun getStrokeWidth(): Double {
        return currentPaint.strokeWidth.toDouble()
    }

    @SuppressLint("WrongThread")
    private fun bitmapToFile(
        bitmap: Bitmap?,
        path: String,
        mimeType: MimeType,
        fileName: String? = "image_${System.currentTimeMillis()}",
    ) {
//        val dir = File(path, "images")
//        if (!dir.exists()) dir.mkdirs()
        val file = File(path)
        if (!(file.exists() && file.isDirectory)) {
            return
            //throw ScreenShotException("Directory '$path' not found!")
        }

        val normalizedPath = path.replace("\\", "/").trimEnd('/')
        val normalizedFileName = fileName?.replace("\\", "/")?.trimStart('/')
        val saveDirectoryPath = "$normalizedPath/"
        val saveFilePath = "$normalizedFileName.${mimeType.extension}"

        when (mimeType) {
            MimeType.PNG -> {
                FileOutputStream("$saveDirectoryPath$saveFilePath").use { outputStream ->
                    bitmap?.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                }
            }
            MimeType.JPEG ->  {
                FileOutputStream("$saveDirectoryPath$saveFilePath").use { outputStream ->
                    bitmap?.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }
            }
            MimeType.BMP -> {
                if(bitmap != null) {
                    exportUtil.createBmpFromBitmap(bitmap, "$saveDirectoryPath$saveFilePath")
                }
            }
            MimeType.PDF ->  {
                if(bitmap != null) {
                    exportUtil.createPdfFromBitmap(bitmap, "$saveDirectoryPath$saveFilePath")
                }
            }
            MimeType.GIF -> {
                if(bitmap != null) {
                    exportUtil.createGifFromBitmap(gifFrames, saveDirectoryPath, saveFilePath)
                }
            }
            MimeType.TIFF -> {
                if(bitmap != null) {
                    exportUtil.createTiffFromBitmap(bitmap, "$saveDirectoryPath$saveFilePath")
                }
            }
        }
    }

    internal fun export(path: String, mimeType: MimeType, fileName: String?) {
        val bitmap = extraBitmap?.copy(extraBitmap!!.config!!, false)
        bitmapToFile(bitmap, path, mimeType, fileName)
    }

    internal fun import(path: String, width: Double?, height: Double?) {
        if (!isPaintBoxViewEnable) return
        val bitmap = BitmapFactory.decodeFile(path)
        val normalizedBitmap = normalizeBitmap(bitmap, maxSize = 2048)

        val exif = ExifInterface(path)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }

        val scaledBitmap = normalizedBitmap.scale(
            width?.toInt() ?: normalizedBitmap.width,
            height?.toInt() ?: normalizedBitmap.height
        )
        val finalBitmap = Bitmap.createBitmap(
            scaledBitmap,
            0,
            0,
            scaledBitmap.width,
            scaledBitmap.height,
            matrix,
            true
        )
        if (extraBitmap == null ||
            extraBitmap?.width != finalBitmap.width ||
            extraBitmap?.height != finalBitmap.height
        ) {
            extraBitmap?.recycle()
            extraBitmap = createBitmap(surfaceWidth, surfaceHeight)
            extraCanvas = Canvas(extraBitmap!!)
        }

        extraCanvas?.drawBitmap(finalBitmap, 0f, 0f, null)
        actions.add(
            DrawAction.ImageAction(finalBitmap, Matrix())
        )
        redrawCachedBitmap()

        redrawSurface()
    }

    internal fun setEnable(isEnable: Boolean) {
        isPaintBoxViewEnable = isEnable
    }

    internal fun isEnable(): Boolean {
        return isPaintBoxViewEnable
    }

    internal fun setPaintMode(paintMode: PaintMode) {
        tool = PaintToolFactory.create(paintMode)
        currentPaint = tool.createPaint(currentPaint, strokeColor)
    }

    internal fun getPaintMode(): PaintMode {
        return tool.paintMode
    }
}