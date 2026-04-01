// app/src/main/java/com/mangalens/ImageEditorView.kt
package com.mangalens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Canvas de edição com:
 *  - Pincel livre
 *  - Blocos de texto estilizados (cor, negrito, itálico, alinhamento, fundo)
 *  - Pinch-to-zoom (dois dedos para ampliar/reduzir)
 *  - Pan (arrastar com um dedo quando não está no modo pincel/texto)
 *  - Exportação para Bitmap nas dimensões originais
 *  - Crop de bordas
 */
@SuppressLint("ClickableViewAccessibility")
class ImageEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ─────────────────────────────────────────────
    // FERRAMENTAS
    // ─────────────────────────────────────────────

    enum class Tool { BRUSH, TEXT, CROP, NONE }

    var activeTool: Tool = Tool.NONE

    // Pincel
    var brushColor: Int  = Color.RED
    var brushSize: Float = 8f

    // Texto
    var textColor: Int                      = Color.WHITE
    var textSizeSp: Float                   = 18f
    var textBold: Boolean                   = false
    var textItalic: Boolean                 = false
    var textAlignment: Layout.Alignment     = Layout.Alignment.ALIGN_CENTER
    var textHasBackground: Boolean          = false
    var textBackgroundAlpha: Int            = 180

    /** Chamado quando o usuário toca para posicionar texto */
    var onTextPositionRequested: ((x: Float, y: Float) -> Unit)? = null

    // ─────────────────────────────────────────────
    // ESTRUTURAS INTERNAS
    // ─────────────────────────────────────────────

    private data class BrushStroke(val path: Path, val paint: Paint)

    data class TextBlock(
        var text: String,
        var x: Float,              // coordenada no espaço da VIEW (não do bitmap)
        var y: Float,
        var color: Int,
        var sizeSp: Float,
        var bold: Boolean,
        var italic: Boolean,
        var alignment: Layout.Alignment,
        var hasBackground: Boolean,
        var bgAlpha: Int,
        var maxWidthPx: Int
    )

    private val strokes    = mutableListOf<BrushStroke>()
    private val textBlocks = mutableListOf<TextBlock>()
    private val undoStack  = mutableListOf<Any>()

    private var currentPath:  Path?  = null
    private var currentPaint: Paint? = null

    // ─────────────────────────────────────────────
    // ZOOM / PAN
    // ─────────────────────────────────────────────

    private var scaleFactor   = 1f
    private val minScale      = 0.5f
    private val maxScale      = 5f
    private var panX          = 0f
    private var panY          = 0f

    // Para o pan com um dedo
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var isPanning = false

    private val scaleGestureDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scaleFactor * detector.scaleFactor)
                    .coerceIn(minScale, maxScale)
                // Zoom centrado no ponto do gesto
                val focusX = detector.focusX
                val focusY = detector.focusY
                panX = focusX - (focusX - panX) * (newScale / scaleFactor)
                panY = focusY - (focusY - panY) * (newScale / scaleFactor)
                scaleFactor = newScale
                invalidate()
                return true
            }
        })

    // ─────────────────────────────────────────────
    // BITMAP DE FUNDO
    // ─────────────────────────────────────────────

    private var backgroundBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Rect do bitmap mapeado para a view (centralizado, fit) */
    private val bitmapDstRect = RectF()

    fun setBackground(bmp: Bitmap) {
        backgroundBitmap = bmp
        requestLayout()
        invalidate()
    }

    private fun updateBitmapDst() {
        val bmp = backgroundBitmap ?: return
        val scaleX = width.toFloat()  / bmp.width
        val scaleY = height.toFloat() / bmp.height
        val scale  = minOf(scaleX, scaleY)
        val dx     = (width  - bmp.width  * scale) / 2f
        val dy     = (height - bmp.height * scale) / 2f
        bitmapDstRect.set(dx, dy, dx + bmp.width * scale, dy + bmp.height * scale)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        updateBitmapDst()
    }

    // ─────────────────────────────────────────────
    // CROP
    // ─────────────────────────────────────────────

    /** Rect de corte em coordenadas da VIEW */
    private var cropRect: RectF? = null
    private var cropHandle: CropHandle = CropHandle.NONE
    private val cropHandleSize = 32f * resources.displayMetrics.density
    private val cropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.WHITE
        style       = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val cropDimPaint = Paint().apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val cropCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.WHITE
        style       = Paint.Style.STROKE
        strokeWidth = 4f
    }

    enum class CropHandle { NONE, TL, TR, BL, BR, MOVE }

    /** Inicializa o rect de corte cobrindo toda a imagem */
    fun initCrop() {
        cropRect = RectF(bitmapDstRect)
        invalidate()
    }

    /** Aplica o crop: recorta o bitmap original e substitui */
    fun applyCrop(): Boolean {
        val cr  = cropRect ?: return false
        val bmp = backgroundBitmap ?: return false

        // Converte coordenadas de view → bitmap original
        val scaleX = bmp.width.toFloat()  / bitmapDstRect.width()
        val scaleY = bmp.height.toFloat() / bitmapDstRect.height()

        val bx = ((cr.left  - bitmapDstRect.left) * scaleX).toInt().coerceIn(0, bmp.width  - 1)
        val by = ((cr.top   - bitmapDstRect.top)  * scaleY).toInt().coerceIn(0, bmp.height - 1)
        val bw = (cr.width()  * scaleX).toInt().coerceIn(1, bmp.width  - bx)
        val bh = (cr.height() * scaleY).toInt().coerceIn(1, bmp.height - by)

        val cropped = Bitmap.createBitmap(bmp, bx, by, bw, bh)
        backgroundBitmap = cropped
        cropRect = null
        activeTool = Tool.NONE
        // Limpa traços e textos pois as coordenadas mudaram
        strokes.clear()
        textBlocks.clear()
        undoStack.clear()
        updateBitmapDst()
        invalidate()
        return true
    }

    // ─────────────────────────────────────────────
    // DRAW
    // ─────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(scaleFactor, scaleFactor,
            (width / 2f - panX) / scaleFactor,
            (height / 2f - panY) / scaleFactor)

        // Fundo
        backgroundBitmap?.let {
            canvas.drawBitmap(it, null, bitmapDstRect, bitmapPaint)
        }

        // Traços
        strokes.forEach { canvas.drawPath(it.path, it.paint) }
        currentPath?.let { canvas.drawPath(it, currentPaint!!) }

        // Textos
        textBlocks.forEach { drawTextBlock(canvas, it) }

        canvas.restore()

        // Crop overlay (fora do zoom para não distorcer)
        drawCropOverlay(canvas)
    }

    private fun drawTextBlock(canvas: Canvas, block: TextBlock) {
        val density = resources.displayMetrics.scaledDensity
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = block.color
            textSize = block.sizeSp * density
            typeface = when {
                block.bold && block.italic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
                block.bold                 -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                block.italic               -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                else                       -> Typeface.DEFAULT
            }
        }
        val maxW = block.maxWidthPx.coerceAtLeast(80)
        val sl   = StaticLayout.Builder
            .obtain(block.text, 0, block.text.length, tp, maxW)
            .setAlignment(block.alignment)
            .setLineSpacing(0f, 1.2f)
            .setIncludePad(false)
            .build()

        canvas.save()
        canvas.translate(block.x, block.y)
        if (block.hasBackground) {
            val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                alpha = block.bgAlpha
            }
            canvas.drawRoundRect(
                RectF(-8f, -4f, sl.width + 8f, sl.height + 4f),
                8f, 8f, bgP
            )
        }
        sl.draw(canvas)
        canvas.restore()
    }

    private fun drawCropOverlay(canvas: Canvas) {
        val cr = cropRect ?: return

        // Escurece fora do crop
        canvas.drawRect(0f, 0f, width.toFloat(), cr.top, cropDimPaint)
        canvas.drawRect(0f, cr.bottom, width.toFloat(), height.toFloat(), cropDimPaint)
        canvas.drawRect(0f, cr.top, cr.left, cr.bottom, cropDimPaint)
        canvas.drawRect(cr.right, cr.top, width.toFloat(), cr.bottom, cropDimPaint)

        // Borda
        canvas.drawRect(cr, cropPaint)

        // Grade 3×3
        val thirdW = cr.width()  / 3f
        val thirdH = cr.height() / 3f
        val gridPaint = Paint(cropPaint).apply { alpha = 80 }
        canvas.drawLine(cr.left + thirdW, cr.top, cr.left + thirdW, cr.bottom, gridPaint)
        canvas.drawLine(cr.left + thirdW * 2, cr.top, cr.left + thirdW * 2, cr.bottom, gridPaint)
        canvas.drawLine(cr.left, cr.top + thirdH, cr.right, cr.top + thirdH, gridPaint)
        canvas.drawLine(cr.left, cr.top + thirdH * 2, cr.right, cr.top + thirdH * 2, gridPaint)

        // Alças nos cantos
        val len = 28f
        val pts = listOf(
            cr.left to cr.top,
            cr.right to cr.top,
            cr.left to cr.bottom,
            cr.right to cr.bottom
        )
        pts.forEach { (x, y) ->
            val sx = if (x == cr.left) 1f else -1f
            val sy = if (y == cr.top)  1f else -1f
            canvas.drawLine(x, y, x + sx * len, y, cropCornerPaint)
            canvas.drawLine(x, y, x, y + sy * len, cropCornerPaint)
        }
    }

    // ─────────────────────────────────────────────
    // TOQUE
    // ─────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Pinch-to-zoom tem prioridade
        scaleGestureDetector.onTouchEvent(event)
        if (scaleGestureDetector.isInProgress) return true

        return when (activeTool) {
            Tool.BRUSH -> handleBrush(event)
            Tool.TEXT  -> handleTextTouch(event)
            Tool.CROP  -> handleCrop(event)
            Tool.NONE  -> handlePan(event)
        }
    }

    // Converte coordenada da view para o espaço transformado (zoom+pan)
    private fun viewToCanvas(x: Float, y: Float): PointF {
        val cx = (x - panX) / scaleFactor
        val cy = (y - panY) / scaleFactor
        return PointF(cx, cy)
    }

    private fun handleBrush(event: MotionEvent): Boolean {
        val pt = viewToCanvas(event.x, event.y)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val p = Path().apply { moveTo(pt.x, pt.y) }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style       = Paint.Style.STROKE
                    strokeCap   = Paint.Cap.ROUND
                    strokeJoin  = Paint.Join.ROUND
                    strokeWidth = brushSize / scaleFactor  // compensa zoom
                    color       = brushColor
                }
                currentPath  = p
                currentPaint = paint
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath?.lineTo(pt.x, pt.y)
            }
            MotionEvent.ACTION_UP -> {
                currentPath?.lineTo(pt.x, pt.y)
                val stroke = BrushStroke(currentPath!!, currentPaint!!)
                strokes.add(stroke)
                undoStack.add(stroke)
                currentPath  = null
                currentPaint = null
            }
        }
        invalidate()
        return true
    }

    private fun handleTextTouch(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val pt = viewToCanvas(event.x, event.y)
            onTextPositionRequested?.invoke(pt.x, pt.y)
        }
        return true
    }

    private fun handlePan(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastPanX  = event.x
                lastPanY  = event.y
                isPanning = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPanning) {
                    panX += event.x - lastPanX
                    panY += event.y - lastPanY
                    lastPanX = event.x
                    lastPanY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false
            }
        }
        return true
    }

    private fun handleCrop(event: MotionEvent): Boolean {
        val cr = cropRect ?: return false
        val x  = event.x; val y = event.y
        val hs = cropHandleSize

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                cropHandle = when {
                    RectF(cr.left  - hs, cr.top    - hs, cr.left  + hs, cr.top    + hs).contains(x, y) -> CropHandle.TL
                    RectF(cr.right - hs, cr.top    - hs, cr.right + hs, cr.top    + hs).contains(x, y) -> CropHandle.TR
                    RectF(cr.left  - hs, cr.bottom - hs, cr.left  + hs, cr.bottom + hs).contains(x, y) -> CropHandle.BL
                    RectF(cr.right - hs, cr.bottom - hs, cr.right + hs, cr.bottom + hs).contains(x, y) -> CropHandle.BR
                    cr.contains(x, y) -> CropHandle.MOVE
                    else              -> CropHandle.NONE
                }
                lastPanX = x; lastPanY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastPanX; val dy = y - lastPanY
                val minSize = 60f
                val bounds  = bitmapDstRect
                when (cropHandle) {
                    CropHandle.TL -> {
                        cr.left = (cr.left + dx).coerceIn(bounds.left, cr.right - minSize)
                        cr.top  = (cr.top  + dy).coerceIn(bounds.top,  cr.bottom - minSize)
                    }
                    CropHandle.TR -> {
                        cr.right = (cr.right + dx).coerceIn(cr.left + minSize, bounds.right)
                        cr.top   = (cr.top   + dy).coerceIn(bounds.top, cr.bottom - minSize)
                    }
                    CropHandle.BL -> {
                        cr.left   = (cr.left   + dx).coerceIn(bounds.left, cr.right - minSize)
                        cr.bottom = (cr.bottom + dy).coerceIn(cr.top + minSize, bounds.bottom)
                    }
                    CropHandle.BR -> {
                        cr.right  = (cr.right  + dx).coerceIn(cr.left + minSize, bounds.right)
                        cr.bottom = (cr.bottom + dy).coerceIn(cr.top + minSize, bounds.bottom)
                    }
                    CropHandle.MOVE -> {
                        val newLeft   = cr.left   + dx
                        val newTop    = cr.top    + dy
                        val newRight  = cr.right  + dx
                        val newBottom = cr.bottom + dy
                        if (newLeft >= bounds.left && newRight <= bounds.right) {
                            cr.left = newLeft; cr.right = newRight
                        }
                        if (newTop >= bounds.top && newBottom <= bounds.bottom) {
                            cr.top = newTop; cr.bottom = newBottom
                        }
                    }
                    CropHandle.NONE -> {}
                }
                lastPanX = x; lastPanY = y
                invalidate()
            }
            MotionEvent.ACTION_UP -> cropHandle = CropHandle.NONE
        }
        return true
    }

    // ─────────────────────────────────────────────
    // API PÚBLICA
    // ─────────────────────────────────────────────

    fun addTextBlock(text: String, x: Float, y: Float) {
        val block = TextBlock(
            text         = text,
            x            = x,
            y            = y,
            color        = textColor,
            sizeSp       = textSizeSp,
            bold         = textBold,
            italic       = textItalic,
            alignment    = textAlignment,
            hasBackground = textHasBackground,
            bgAlpha      = textBackgroundAlpha,
            maxWidthPx   = (width * 0.75f / scaleFactor).toInt().coerceAtLeast(200)
        )
        textBlocks.add(block)
        undoStack.add(block)
        invalidate()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        when (val last = undoStack.removeLast()) {
            is BrushStroke -> strokes.remove(last)
            is TextBlock   -> textBlocks.remove(last)
        }
        invalidate()
    }

    fun resetZoom() {
        scaleFactor = 1f; panX = 0f; panY = 0f; invalidate()
    }

    fun clearAll() {
        strokes.clear(); textBlocks.clear(); undoStack.clear()
        currentPath = null; currentPaint = null
        cropRect = null
        invalidate()
    }

    /**
     * Exporta a imagem em resolução original.
     * Traços e textos são re-renderizados escalados para o tamanho real do bitmap.
     */
    fun exportBitmap(): Bitmap {
        val bmp  = backgroundBitmap
        val outW = bmp?.width  ?: width
        val outH = bmp?.height ?: height

        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        bmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        if (bmp == null || bitmapDstRect.width() == 0f) return result

        // Fator de escala: espaço da view → espaço do bitmap original
        val sx = outW.toFloat() / bitmapDstRect.width()
        val sy = outH.toFloat() / bitmapDstRect.height()
        val ox = bitmapDstRect.left
        val oy = bitmapDstRect.top

        canvas.save()
        canvas.scale(sx, sy)
        canvas.translate(-ox, -oy)

        strokes.forEach { canvas.drawPath(it.path, it.paint) }
        textBlocks.forEach { drawTextBlock(canvas, it) }

        canvas.restore()
        return result
    }
}