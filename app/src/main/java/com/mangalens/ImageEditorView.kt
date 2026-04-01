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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Canvas de edição — versão 2.0
 *
 * MUDANÇAS PRINCIPAIS:
 *
 * 1. TRANSFORMAÇÃO CANÔNICA (zoom/pan)
 *    Antes: canvas.translate(pan) + canvas.scale(sf, pivot_complexo)
 *    Agora: canvas.translate(pan) + canvas.scale(sf, sf)   ← pivot = origem
 *    Fórmula: screen = canvas × scaleFactor + pan
 *    viewToCanvas: cx = (sx − panX) / scaleFactor
 *    → Zoom sempre centrado no ponto exato entre os dois dedos (focusX/Y do detector).
 *
 * 2. BLOCOS DE TEXTO SELECIONÁVEIS / ARRASTÁVEIS / REDIMENSIONÁVEIS
 *    - Toque em bloco existente → seleciona e arrastra (MOVE)
 *    - Handles nos 4 cantos visíveis ao selecionar
 *    - Arrastar handle direito (TR / BR) → redimensiona largura pela direita
 *    - Arrastar handle esquerdo (TL / BL) → redimensiona largura pela esquerda (move x)
 *    - Toque em área vazia → deseleciona e abre dialog para novo bloco
 *    - Handles têm raio fixo em TELA (independem do zoom)
 *
 * 3. CROP COM SAFE AREA
 *    - `bottomSafeAreaPx`: altura do toolbar inferior em px
 *    - A borda inferior do crop nunca cobre o toolbar
 *    - Linha tracejada amarela indica o limite de segurança
 *
 * 4. EXPORTAÇÃO
 *    - Remapeia canvas space → bitmap original com escala correta
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

    private enum class TextManipMode { NONE, MOVE, RESIZE_LEFT, RESIZE_RIGHT }

    var activeTool: Tool = Tool.NONE

    // Pincel
    var brushColor: Int   = Color.RED
    var brushSize: Float  = 8f

    // Texto
    var textColor: Int                  = Color.WHITE
    var textSizeSp: Float               = 18f
    var textBold: Boolean               = false
    var textItalic: Boolean             = false
    var textAlignment: Layout.Alignment = Layout.Alignment.ALIGN_CENTER
    var textHasBackground: Boolean      = false
    var textBackgroundAlpha: Int        = 180

    /** Chamado quando o usuário toca em área vazia no modo TEXT */
    var onTextPositionRequested: ((x: Float, y: Float) -> Unit)? = null

    /**
     * Altura do toolbar inferior (px).
     * Definir DEPOIS do layout para proteger o crop.
     */
    var bottomSafeAreaPx: Int = 0

    // ─────────────────────────────────────────────
    // ESTRUTURAS INTERNAS
    // ─────────────────────────────────────────────

    private data class BrushStroke(val path: Path, val paint: Paint)

    data class TextBlock(
        var text: String,
        var x: Float,
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
    val textBlocks         = mutableListOf<TextBlock>()
    private val undoStack  = mutableListOf<Any>()

    private var currentPath:  Path?  = null
    private var currentPaint: Paint? = null

    // ─────────────────────────────────────────────
    // ZOOM / PAN  (transformação canônica)
    // ─────────────────────────────────────────────

    private var scaleFactor = 1f
    private val minScale    = 0.3f
    private val maxScale    = 10f
    private var panX        = 0f
    private var panY        = 0f

    // Para pan com um dedo (mode NONE ou entre gestos)
    private var lastPanX  = 0f
    private var lastPanY  = 0f
    private var isPanning = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val newScale = (scaleFactor * d.scaleFactor).coerceIn(minScale, maxScale)
                val ratio    = newScale / scaleFactor
                // Mantém o ponto focal fixo na tela → zoom centrado nos dedos
                panX = d.focusX - (d.focusX - panX) * ratio
                panY = d.focusY - (d.focusY - panY) * ratio
                scaleFactor  = newScale
                invalidate()
                return true
            }
        })

    /** screen → canvas  */
    private fun viewToCanvas(sx: Float, sy: Float) =
        PointF((sx - panX) / scaleFactor, (sy - panY) / scaleFactor)

    // ─────────────────────────────────────────────
    // BITMAP DE FUNDO
    // ─────────────────────────────────────────────

    private var backgroundBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Rect do bitmap em canvas space (= view px a sf=1, pan=0) */
    val bitmapDstRect = RectF()

    fun setBackground(bmp: Bitmap) {
        backgroundBitmap = bmp
        requestLayout()
        invalidate()
    }

    private fun updateBitmapDst() {
        val bmp = backgroundBitmap ?: return
        val s  = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dx = (width  - bmp.width  * s) / 2f
        val dy = (height - bmp.height * s) / 2f
        bitmapDstRect.set(dx, dy, dx + bmp.width * s, dy + bmp.height * s)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        updateBitmapDst()
    }

    // ─────────────────────────────────────────────
    // SELEÇÃO / MANIPULAÇÃO DE TEXTO
    // ─────────────────────────────────────────────

    /** Rects de cada bloco em canvas space — reconstruído no onDraw */
    private val blockRenderRects = ArrayList<RectF>()

    var selectedIdx      = -1
        private set
    private var textManipMode = TextManipMode.NONE
    private var manipLastX    = 0f
    private var manipLastY    = 0f

    // Estado de "toque pendente em área vazia" → abre dialog ao soltar
    private var newTextPending = false
    private var pendingTapX    = 0f
    private var pendingTapY    = 0f

    private val px get() = resources.displayMetrics.density
    private val spx get() = resources.displayMetrics.scaledDensity

    /** Raio visual dos handles em canvas space (fixo em tela) */
    private val handleRadiusCanvas get() = 14f * px / scaleFactor

    /** Raio de hit para handles (ligeiramente maior que o visual) */
    private val handleHitCanvas get() = 24f * px / scaleFactor

    // ─────────────────────────────────────────────
    // CROP
    // ─────────────────────────────────────────────

    private var cropRect: RectF? = null
    private var cropHandle       = CropHandle.NONE
    private val cropHitSize get() = 38f * px  // screen space
    private val minCropSize      = 60f

    enum class CropHandle { NONE, TL, TR, BL, BR, MOVE }

    private fun maxCropBottom() =
        (height.toFloat() - bottomSafeAreaPx.toFloat()).coerceAtMost(bitmapDstRect.bottom)

    // ─────────────────────────────────────────────
    // PAINTS (estáticos)
    // ─────────────────────────────────────────────

    private val cropBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val cropDimPaint    = Paint().apply { color = Color.argb(130, 0, 0, 0) }
    private val cropCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val cropGridPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val safeLinePaint   = Paint().apply {
        color = Color.argb(140, 255, 200, 0); style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(14f, 7f), 0f) }

    private val selBorderPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL }
    private val handleRimPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6200EE"); style = Paint.Style.STROKE }

    // ─────────────────────────────────────────────
    // CROP — API
    // ─────────────────────────────────────────────

    fun initCrop() {
        cropRect = RectF(bitmapDstRect).apply {
            bottom = bottom.coerceAtMost(maxCropBottom())
        }
        invalidate()
    }

    fun applyCrop(): Boolean {
        val cr  = cropRect ?: return false
        val bmp = backgroundBitmap ?: return false

        val sx = bmp.width.toFloat()  / bitmapDstRect.width()
        val sy = bmp.height.toFloat() / bitmapDstRect.height()

        val bx  = ((cr.left   - bitmapDstRect.left) * sx).toInt().coerceIn(0, bmp.width  - 1)
        val by_ = ((cr.top    - bitmapDstRect.top)  * sy).toInt().coerceIn(0, bmp.height - 1)
        val bw  = (cr.width()  * sx).toInt().coerceIn(1, bmp.width  - bx)
        val bh  = (cr.height() * sy).toInt().coerceIn(1, bmp.height - by_)

        backgroundBitmap = Bitmap.createBitmap(bmp, bx, by_, bw, bh)
        cropRect = null; activeTool = Tool.NONE
        strokes.clear(); textBlocks.clear(); undoStack.clear()
        blockRenderRects.clear(); selectedIdx = -1
        updateBitmapDst(); invalidate()
        return true
    }

    // ─────────────────────────────────────────────
    // DRAW
    // ─────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // ── Transformação canônica: screen = canvas × sf + pan ──
        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(scaleFactor, scaleFactor)

        // Bitmap de fundo
        backgroundBitmap?.let { canvas.drawBitmap(it, null, bitmapDstRect, bitmapPaint) }

        // Traços de pincel
        strokes.forEach { canvas.drawPath(it.path, it.paint) }
        currentPath?.let { canvas.drawPath(it, currentPaint!!) }

        // Blocos de texto — rect calculado e armazenado para hit-test
        blockRenderRects.clear()
        textBlocks.forEachIndexed { i, block ->
            blockRenderRects.add(
                drawTextBlock(canvas, block,
                    selected = (i == selectedIdx && activeTool == Tool.TEXT))
            )
        }

        canvas.restore()

        // Crop overlay em screen space (não sofre zoom/pan)
        drawCropOverlay(canvas)
    }

    /**
     * Desenha um bloco de texto e retorna seu rect em canvas space.
     * Usado tanto no onDraw quanto na exportação.
     */
    private fun drawTextBlock(canvas: Canvas, block: TextBlock, selected: Boolean): RectF {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = block.color
            textSize = block.sizeSp * spx
            typeface = when {
                block.bold && block.italic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
                block.bold   -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                block.italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                else         -> Typeface.DEFAULT
            }
        }

        val maxW = block.maxWidthPx.coerceAtLeast(60)
        val sl   = StaticLayout.Builder
            .obtain(block.text, 0, block.text.length, tp, maxW)
            .setAlignment(block.alignment)
            .setLineSpacing(0f, 1.2f)
            .setIncludePad(false)
            .build()

        val padX   = 8f
        val padY   = 4f
        val blockRect = RectF(
            block.x - padX, block.y - padY,
            block.x + maxW + padX, block.y + sl.height + padY
        )

        canvas.save()
        canvas.translate(block.x, block.y)

        if (block.hasBackground) {
            canvas.drawRoundRect(
                RectF(-padX, -padY, maxW + padX, sl.height + padY), 8f, 8f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK; alpha = block.bgAlpha }
            )
        }
        sl.draw(canvas)
        canvas.restore()

        if (selected) drawSelectionHandles(canvas, blockRect)

        return blockRect
    }

    /** Handles de seleção em canvas space (tamanho fixo na tela via / scaleFactor) */
    private fun drawSelectionHandles(canvas: Canvas, rect: RectF) {
        val sw = 2f / scaleFactor
        val hr = handleRadiusCanvas

        // Borda tracejada
        selBorderPaint.apply {
            strokeWidth = sw
            pathEffect  = DashPathEffect(
                floatArrayOf(8f / scaleFactor, 4f / scaleFactor), 0f)
        }
        canvas.drawRect(rect, selBorderPaint)

        handleRimPaint.strokeWidth = sw * 1.5f

        // 4 handles de canto
        listOf(
            rect.left to rect.top,
            rect.right to rect.top,
            rect.left to rect.bottom,
            rect.right to rect.bottom
        ).forEach { (hx, hy) ->
            canvas.drawCircle(hx, hy, hr, handleFillPaint)
            canvas.drawCircle(hx, hy, hr, handleRimPaint)
        }
    }

    /** Overlay de corte em screen space */
    private fun drawCropOverlay(canvas: Canvas) {
        val cr = cropRect ?: return
        val w  = width.toFloat(); val h = height.toFloat()

        // Escurece fora do crop
        canvas.drawRect(0f, 0f, w, cr.top, cropDimPaint)
        canvas.drawRect(0f, cr.bottom, w, h, cropDimPaint)
        canvas.drawRect(0f, cr.top, cr.left, cr.bottom, cropDimPaint)
        canvas.drawRect(cr.right, cr.top, w, cr.bottom, cropDimPaint)

        canvas.drawRect(cr, cropBorderPaint)

        // Grade 3×3
        val tw = cr.width() / 3f; val th = cr.height() / 3f
        canvas.drawLine(cr.left + tw,   cr.top, cr.left + tw,   cr.bottom, cropGridPaint)
        canvas.drawLine(cr.left + tw*2, cr.top, cr.left + tw*2, cr.bottom, cropGridPaint)
        canvas.drawLine(cr.left, cr.top + th,   cr.right, cr.top + th,   cropGridPaint)
        canvas.drawLine(cr.left, cr.top + th*2, cr.right, cr.top + th*2, cropGridPaint)

        // Alças em L nos cantos
        val len = 30f
        listOf(cr.left to cr.top, cr.right to cr.top,
            cr.left to cr.bottom, cr.right to cr.bottom)
            .forEach { (x, y) ->
                val sx = if (x == cr.left) 1f else -1f
                val sy = if (y == cr.top)  1f else -1f
                canvas.drawLine(x, y, x + sx * len, y, cropCornerPaint)
                canvas.drawLine(x, y, x, y + sy * len, cropCornerPaint)
            }

        // Linha de safe area do toolbar
        if (bottomSafeAreaPx > 0) {
            val safeLine = h - bottomSafeAreaPx
            canvas.drawLine(0f, safeLine, w, safeLine, safeLinePaint)
        }
    }

    // ─────────────────────────────────────────────
    // TOUCH
    // ─────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) return true

        return when (activeTool) {
            Tool.BRUSH -> handleBrush(event)
            Tool.TEXT  -> handleText(event)
            Tool.CROP  -> handleCrop(event)
            Tool.NONE  -> handlePan(event)
        }
    }

    // ── Pincel ───────────────────────────────────

    private fun handleBrush(event: MotionEvent): Boolean {
        val pt = viewToCanvas(event.x, event.y)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val path  = Path().apply { moveTo(pt.x, pt.y) }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style       = Paint.Style.STROKE
                    strokeCap   = Paint.Cap.ROUND
                    strokeJoin  = Paint.Join.ROUND
                    strokeWidth = brushSize / scaleFactor
                    color       = brushColor
                }
                currentPath = path; currentPaint = paint
            }
            MotionEvent.ACTION_MOVE -> currentPath?.lineTo(pt.x, pt.y)
            MotionEvent.ACTION_UP   -> {
                currentPath?.lineTo(pt.x, pt.y)
                BrushStroke(currentPath!!, currentPaint!!).also {
                    strokes.add(it); undoStack.add(it)
                }
                currentPath = null; currentPaint = null
            }
        }
        invalidate()
        return true
    }

    // ── Texto (com seleção / drag / resize) ──────

    private fun handleText(event: MotionEvent): Boolean {
        val pt = viewToCanvas(event.x, event.y)
        val hr = handleHitCanvas

        when (event.action) {

            MotionEvent.ACTION_DOWN -> {
                newTextPending = false

                // 1. Handles do bloco atualmente selecionado têm prioridade
                if (selectedIdx in textBlocks.indices) {
                    val rect = blockRenderRects.getOrNull(selectedIdx)
                    if (rect != null) {
                        val hitR = dist(pt, rect.right, rect.top)   < hr ||
                                dist(pt, rect.right, rect.bottom) < hr
                        val hitL = dist(pt, rect.left,  rect.top)   < hr ||
                                dist(pt, rect.left,  rect.bottom) < hr

                        when {
                            hitR -> { textManipMode = TextManipMode.RESIZE_RIGHT
                                storeManip(pt); invalidate(); return true }
                            hitL -> { textManipMode = TextManipMode.RESIZE_LEFT
                                storeManip(pt); invalidate(); return true }
                            rect.contains(pt.x, pt.y) -> {
                                textManipMode = TextManipMode.MOVE
                                storeManip(pt); invalidate(); return true
                            }
                        }
                    }
                }

                // 2. Verifica todos os blocos
                val hitIdx = blockRenderRects.indices.firstOrNull { i ->
                    blockRenderRects.getOrNull(i)?.contains(pt.x, pt.y) == true
                } ?: -1

                if (hitIdx >= 0) {
                    selectedIdx   = hitIdx
                    textManipMode = TextManipMode.MOVE
                    storeManip(pt)
                } else {
                    selectedIdx   = -1
                    textManipMode = TextManipMode.NONE
                    // Guarda posição para criar texto ao soltar (se não houver movimento)
                    newTextPending = true
                    pendingTapX    = pt.x
                    pendingTapY    = pt.y
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = pt.x - manipLastX
                val dy = pt.y - manipLastY

                // Cancela "new text" se o dedo se moveu (tolerância = 8 canvas units)
                if (newTextPending && textManipMode == TextManipMode.NONE) {
                    if (dist2(pt.x - pendingTapX, pt.y - pendingTapY) > (8f / scaleFactor)) {
                        newTextPending = false
                    }
                }

                if (selectedIdx in textBlocks.indices) {
                    val block = textBlocks[selectedIdx]
                    when (textManipMode) {
                        TextManipMode.MOVE -> {
                            block.x += dx; block.y += dy
                            newTextPending = false
                            invalidate()
                        }
                        TextManipMode.RESIZE_RIGHT -> {
                            block.maxWidthPx = (block.maxWidthPx + dx).toInt().coerceAtLeast(60)
                            newTextPending   = false
                            invalidate()
                        }
                        TextManipMode.RESIZE_LEFT -> {
                            val di = dx.toInt()
                            block.x        += di
                            block.maxWidthPx = (block.maxWidthPx - di).coerceAtLeast(60)
                            newTextPending   = false
                            invalidate()
                        }
                        else -> {}
                    }
                }
                storeManip(pt)
            }

            MotionEvent.ACTION_UP -> {
                if (newTextPending && textManipMode == TextManipMode.NONE) {
                    onTextPositionRequested?.invoke(pendingTapX, pendingTapY)
                }
                newTextPending = false
                textManipMode  = TextManipMode.NONE
            }
        }
        return true
    }

    private fun storeManip(pt: PointF) { manipLastX = pt.x; manipLastY = pt.y }

    // ── Pan (Tool.NONE) ───────────────────────────

    private fun handlePan(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN  -> { lastPanX = event.x; lastPanY = event.y; isPanning = true }
            MotionEvent.ACTION_MOVE  -> if (isPanning) {
                panX += event.x - lastPanX; panY += event.y - lastPanY
                lastPanX = event.x; lastPanY = event.y; invalidate()
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> isPanning = false
        }
        return true
    }

    // ── Crop ─────────────────────────────────────

    private fun handleCrop(event: MotionEvent): Boolean {
        val cr  = cropRect ?: return false
        val x   = event.x; val y = event.y
        val hs  = cropHitSize
        val mb  = maxCropBottom()
        val bl  = bitmapDstRect

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                cropHandle = when {
                    boxHit(x, y, cr.left,  cr.top,    hs) -> CropHandle.TL
                    boxHit(x, y, cr.right, cr.top,    hs) -> CropHandle.TR
                    boxHit(x, y, cr.left,  cr.bottom, hs) -> CropHandle.BL
                    boxHit(x, y, cr.right, cr.bottom, hs) -> CropHandle.BR
                    cr.contains(x, y) -> CropHandle.MOVE
                    else              -> CropHandle.NONE
                }
                lastPanX = x; lastPanY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastPanX; val dy = y - lastPanY
                when (cropHandle) {
                    CropHandle.TL -> {
                        cr.left = (cr.left + dx).coerceIn(bl.left,  cr.right  - minCropSize)
                        cr.top  = (cr.top  + dy).coerceIn(bl.top,   cr.bottom - minCropSize)
                    }
                    CropHandle.TR -> {
                        cr.right = (cr.right + dx).coerceIn(cr.left + minCropSize, bl.right)
                        cr.top   = (cr.top   + dy).coerceIn(bl.top, cr.bottom - minCropSize)
                    }
                    CropHandle.BL -> {
                        cr.left   = (cr.left   + dx).coerceIn(bl.left, cr.right - minCropSize)
                        cr.bottom = (cr.bottom + dy).coerceIn(cr.top + minCropSize, mb)
                    }
                    CropHandle.BR -> {
                        cr.right  = (cr.right  + dx).coerceIn(cr.left + minCropSize, bl.right)
                        cr.bottom = (cr.bottom + dy).coerceIn(cr.top + minCropSize, mb)
                    }
                    CropHandle.MOVE -> {
                        val nL = cr.left + dx; val nT = cr.top + dy
                        val nR = cr.right + dx; val nB = cr.bottom + dy
                        if (nL >= bl.left && nR <= bl.right)  { cr.left = nL; cr.right  = nR }
                        if (nT >= bl.top  && nB <= mb)        { cr.top  = nT; cr.bottom = nB }
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
        val defaultW = (bitmapDstRect.width() * 0.75f).toInt().coerceAtLeast(200)
        TextBlock(
            text = text, x = x, y = y, color = textColor,
            sizeSp = textSizeSp, bold = textBold, italic = textItalic,
            alignment = textAlignment, hasBackground = textHasBackground,
            bgAlpha = textBackgroundAlpha, maxWidthPx = defaultW
        ).also { block ->
            textBlocks.add(block)
            undoStack.add(block)
            selectedIdx   = textBlocks.lastIndex
            textManipMode = TextManipMode.NONE
        }
        invalidate()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        when (val last = undoStack.removeLast()) {
            is BrushStroke -> strokes.remove(last)
            is TextBlock   -> { textBlocks.remove(last); selectedIdx = -1 }
        }
        invalidate()
    }

    fun resetZoom() { scaleFactor = 1f; panX = 0f; panY = 0f; invalidate() }

    fun clearAll() {
        strokes.clear(); textBlocks.clear(); undoStack.clear()
        blockRenderRects.clear(); selectedIdx = -1
        currentPath = null; currentPaint = null; cropRect = null
        invalidate()
    }

    /**
     * Exporta em resolução original.
     * Remapeia canvas space → bitmap pixel via scale+translate.
     */
    fun exportBitmap(): Bitmap {
        val bmp  = backgroundBitmap
        val outW = bmp?.width  ?: width
        val outH = bmp?.height ?: height

        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        bmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        if (bmp == null || bitmapDstRect.width() == 0f) return result

        // canvas space → bitmap original: scale e offset
        val sx = outW.toFloat() / bitmapDstRect.width()
        val sy = outH.toFloat() / bitmapDstRect.height()

        canvas.save()
        canvas.scale(sx, sy)
        canvas.translate(-bitmapDstRect.left, -bitmapDstRect.top)
        strokes.forEach { canvas.drawPath(it.path, it.paint) }
        textBlocks.forEach { drawTextBlock(canvas, it, false) }
        canvas.restore()

        return result
    }

    // ─────────────────────────────────────────────
    // UTILITÁRIOS
    // ─────────────────────────────────────────────

    private fun dist(pt: PointF, x2: Float, y2: Float): Float =
        sqrt(((pt.x - x2) * (pt.x - x2) + (pt.y - y2) * (pt.y - y2)).toDouble()).toFloat()

    private fun dist2(dx: Float, dy: Float): Float =
        sqrt((dx * dx + dy * dy).toDouble()).toFloat()

    private fun boxHit(tx: Float, ty: Float, cx: Float, cy: Float, r: Float): Boolean =
        abs(tx - cx) < r / 2 && abs(ty - cy) < r / 2
}