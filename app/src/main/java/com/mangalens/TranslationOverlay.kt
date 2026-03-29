package com.mangalens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.graphics.PixelFormat
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object TranslationOverlay {

    private var overlayView: View? = null
    private var windowManagerRef: WindowManager? = null
    private var editDialogView: View? = null
    private var copyMenuView: View? = null

    fun show(
        context: Context,
        windowManager: WindowManager,
        results: List<TextResult>,
        screenWidth: Int  = context.resources.displayMetrics.widthPixels,
        screenHeight: Int = context.resources.displayMetrics.heightPixels,
        cropRect: Rect?   = null,
        bitmapWidth: Int  = screenWidth,
        bitmapHeight: Int = screenHeight,
        readOnly: Boolean = false
    ) {
        dismiss(windowManager)
        if (results.isEmpty()) return

        windowManagerRef = windowManager
        val mutableResults = results.map { it.copy() }.toMutableList()

        val drawView = TranslationDrawView(
            context       = context,
            results       = mutableResults,
            screenWidth   = screenWidth,
            screenHeight  = screenHeight,
            cropRect      = cropRect,
            bitmapWidth   = bitmapWidth,
            bitmapHeight  = bitmapHeight,
            readOnly      = readOnly,
            onDismiss     = { dismiss(windowManager) },
            onCopyMenu    = { idx, original, translated ->
                if (!readOnly) showCopyMenu(context, windowManager, idx, original, translated, mutableResults)
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        windowManager.addView(drawView, params)
        overlayView = drawView
    }

    // ─────────────────────────────────────────────
    // MENU GRADE 2×2
    // ─────────────────────────────────────────────

    private fun showCopyMenu(
        context: Context,
        windowManager: WindowManager,
        resultIndex: Int,
        original: String,
        translated: String,
        mutableResults: MutableList<TextResult>
    ) {
        dismissCopyMenu(windowManager)

        val menuView = CopyMenuView(
            context           = context,
            original          = original,
            translated        = translated,
            onCopyOriginal    = { copyToClipboard(context, original, "Original EN"); dismissCopyMenu(windowManager) },
            onCopyTranslation = { copyToClipboard(context, translated, "Tradução PT"); dismissCopyMenu(windowManager) },
            onEditOriginal    = {
                dismissCopyMenu(windowManager)
                showEditDialog(context, windowManager, "✏️  Corrigir texto original (EN)", original) { newOriginal ->
                    mutableResults[resultIndex] = mutableResults[resultIndex].copy(originalText = newOriginal)
                    OcrProcessor.retranslate(newOriginal) { newTranslation ->
                        mutableResults[resultIndex] = mutableResults[resultIndex].copy(translatedText = newTranslation)
                        (overlayView as? TranslationDrawView)?.apply { markEdited(resultIndex); invalidate() }
                    }
                }
            },
            onEditTranslation = {
                dismissCopyMenu(windowManager)
                showEditDialog(context, windowManager, "✏️  Corrigir tradução (PT)", translated) { newText ->
                    mutableResults[resultIndex] = mutableResults[resultIndex].copy(translatedText = newText)
                    (overlayView as? TranslationDrawView)?.apply { markEdited(resultIndex); invalidate() }
                }
            },
            onDismiss = { dismissCopyMenu(windowManager) }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        windowManager.addView(menuView, params)
        copyMenuView = menuView
    }

    private fun dismissCopyMenu(windowManager: WindowManager) {
        copyMenuView?.let { runCatching { windowManager.removeView(it) } }
        copyMenuView = null
    }

    private fun showEditDialog(
        context: Context,
        windowManager: WindowManager,
        title: String,
        currentText: String,
        onConfirm: (String) -> Unit
    ) {
        dismissEditDialog(windowManager)
        val density = context.resources.displayMetrics.density

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16*density).toInt(), (16*density).toInt(), (16*density).toInt(), (16*density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(245, 25, 25, 45)); cornerRadius = 16f * density
            }
        }
        container.addView(TextView(context).apply {
            text = title; textSize = 14f
            setTextColor(Color.argb(200, 180, 180, 255))
            setPadding(0, 0, 0, (10*density).toInt())
        })
        val editText = EditText(context).apply {
            setText(currentText); textSize = 15f
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            hint = "Digite o texto corrigido..."; background = null
            setBackgroundColor(Color.argb(80, 100, 100, 180))
            setPadding((12*density).toInt(), (10*density).toInt(), (12*density).toInt(), (10*density).toInt())
            maxLines = 5; isSingleLine = false; setSelection(currentText.length)
        }
        container.addView(editText)
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, (12*density).toInt(), 0, 0) }
        btnRow.addView(TextView(context).apply {
            text = "Cancelar"; textSize = 13f; setTextColor(Color.argb(180, 180, 180, 180))
            setPadding((16*density).toInt(), (10*density).toInt(), (16*density).toInt(), (10*density).toInt())
            setOnClickListener { dismissEditDialog(windowManager) }
        })
        btnRow.addView(TextView(context).apply {
            text = "✓  Confirmar"; textSize = 13f; setTextColor(Color.argb(255, 130, 200, 130))
            setPadding((16*density).toInt(), (10*density).toInt(), (16*density).toInt(), (10*density).toInt())
            setOnClickListener {
                val t = editText.text.toString().trim()
                if (t.isNotEmpty()) { dismissEditDialog(windowManager); onConfirm(t); Toast.makeText(context, "✅ Texto corrigido", Toast.LENGTH_SHORT).show() }
            }
        })
        container.addView(btnRow)

        val params = WindowManager.LayoutParams(
            (300*density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        windowManager.addView(container, params)
        editDialogView = container
        Handler(Looper.getMainLooper()).postDelayed({
            editText.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    private fun dismissEditDialog(windowManager: WindowManager) {
        editDialogView?.let { v ->
            runCatching { (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) }
            runCatching { windowManager.removeView(v) }
        }
        editDialogView = null
    }

    fun hide()    { overlayView?.visibility = View.INVISIBLE; copyMenuView?.visibility = View.INVISIBLE; editDialogView?.visibility = View.INVISIBLE }
    fun restore() { overlayView?.visibility = View.VISIBLE;   copyMenuView?.visibility = View.VISIBLE;   editDialogView?.visibility = View.VISIBLE   }

    fun dismiss(windowManager: WindowManager) {
        dismissEditDialog(windowManager); dismissCopyMenu(windowManager)
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null; windowManagerRef = null
    }

    fun isVisible(): Boolean = overlayView?.visibility == View.VISIBLE

    fun copyToClipboard(context: Context, text: String, label: String = "MangaLens") {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "📋 Copiado: \"${text.take(40)}\"", Toast.LENGTH_SHORT).show()
    }
}

// ─────────────────────────────────────────────────────
// VIEW DE DESENHO — com anti-colisão de bolhas
// ─────────────────────────────────────────────────────

@SuppressLint("ClickableViewAccessibility")
class TranslationDrawView(
    context: Context,
    private val results: MutableList<TextResult>,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val cropRect: Rect?,
    private val bitmapWidth: Int,
    private val bitmapHeight: Int,
    private val readOnly: Boolean = false,
    private val onDismiss: () -> Unit,
    private val onCopyMenu: (index: Int, original: String, translated: String) -> Unit
) : View(context) {

    private val density = context.resources.displayMetrics.density

    private val bitmapScaleX: Float get() {
        val bmpW     = if (bitmapWidth  > 0) bitmapWidth  else screenWidth
        val displayW = cropRect?.width() ?: screenWidth
        return displayW.toFloat() / bmpW.toFloat()
    }
    private val bitmapScaleY: Float get() {
        val bmpH     = if (bitmapHeight > 0) bitmapHeight else screenHeight
        val displayH = cropRect?.height() ?: screenHeight
        return displayH.toFloat() / bmpH.toFloat()
    }

    private val bubblePaint         = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 255, 248); style = Paint.Style.FILL }
    private val bubbleStrokePaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 80, 80, 200);  style = Paint.Style.STROKE; strokeWidth = 2f }
    private val bubbleStrokeROPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(100, 130, 130, 130); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val editedStrokePaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 50, 180, 80); style = Paint.Style.STROKE; strokeWidth = 2.5f }
    private val textPaint           = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 15, 15, 70); textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val editedTextPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 10, 80, 20); textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val hintPaint           = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 255, 255, 255); textSize = 10f * density
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }
    private val coverPaint          = Paint().apply { color = Color.argb(12, 0, 0, 0); style = Paint.Style.FILL }
    private val highlightPaint      = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(50, 80, 80, 220); style = Paint.Style.FILL }

    private val padH    = 8f * density
    private val padV    = 5f * density
    private val cornerR = 7f * density
    // Margem mínima vertical entre bolhas vizinhas (evita encavalamento)
    private val bubbleGap = 4f * density

    private data class BubbleHitBox(val rect: RectF, val index: Int)
    private val hitBoxes         = mutableListOf<BubbleHitBox>()
    private var highlightedIndex = -1
    private var longPressRunnable: Runnable? = null
    private val editedIndices    = mutableSetOf<Int>()

    fun markEdited(index: Int) { editedIndices.add(index) }

    init {
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val idx = hitBoxes.indexOfFirst { it.rect.contains(event.x, event.y) }
                    if (idx >= 0) {
                        highlightedIndex = hitBoxes[idx].index; invalidate()
                        if (!readOnly) {
                            longPressRunnable = Runnable {
                                TranslationOverlay.copyToClipboard(context, results[hitBoxes[idx].index].originalText, "Original EN")
                                highlightedIndex = -1; invalidate()
                            }.also { postDelayed(it, 500L) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPressRunnable)
                    if (readOnly) {
                        highlightedIndex = -1; invalidate(); onDismiss()
                    } else {
                        val hit = hitBoxes.firstOrNull { it.rect.contains(event.x, event.y) }
                        if (hit != null && hit.index == highlightedIndex) {
                            val r = results[hit.index]; onCopyMenu(hit.index, r.originalText, r.translatedText)
                        } else if (hit == null) { onDismiss() }
                        highlightedIndex = -1; invalidate()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPressRunnable); highlightedIndex = -1; invalidate(); true }
                else -> false
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitBoxes.clear()

        val offsetX = cropRect?.left?.toFloat()  ?: 0f
        val offsetY = cropRect?.top?.toFloat()   ?: 0f

        // ── 1. Calcula as posições ideais de cada bolha ───────────────────
        data class BubbleLayout(
            val idx: Int,
            val srcLeft: Float, val srcTop: Float, val srcRight: Float, val srcBottom: Float,
            val paint: Paint, val lines: List<String>, val lineH: Float,
            val boxW: Float, val boxH: Float,
            var bubbleLeft: Float, var bubbleTop: Float
        )

        val layouts = mutableListOf<BubbleLayout>()

        results.forEachIndexed { idx, result ->
            val box = result.boundingBox ?: return@forEachIndexed
            val translation = result.translatedText.ifBlank { result.originalText }
            val paint = if (idx in editedIndices) editedTextPaint else textPaint

            val sL = box.left   * bitmapScaleX + offsetX
            val sT = box.top    * bitmapScaleY + offsetY
            val sR = box.right  * bitmapScaleX + offsetX
            val sB = box.bottom * bitmapScaleY + offsetY

            val boxW   = (sR - sL).coerceAtLeast(60f * density)
            val lines  = wrapText(translation, paint, boxW - padH * 2)
            val lineH  = paint.fontSpacing
            val boxH   = lines.size * lineH + padV * 2

            // Posição preferida: acima do balão fonte; se não cabe, abaixo
            var bTop = sT - boxH - 2f * density
            if (bTop < 0f) bTop = sB + 2f * density
            if (bTop + boxH > screenHeight) bTop = sT

            val bLeft = sL.coerceIn(0f, (screenWidth - boxW).coerceAtLeast(0f))

            layouts.add(BubbleLayout(idx, sL, sT, sR, sB, paint, lines, lineH, boxW, boxH, bLeft, bTop))
        }

        // ── 2. Anti-colisão: ordena por top e empurra bolhas que colidam ──
        //
        // ALGORITMO:
        // Percorre as bolhas ordenadas de cima para baixo (por bubbleTop).
        // Para cada bolha, verifica se sobrepõe alguma bolha já posicionada.
        // Se sim, empurra para baixo até não haver sobreposição.
        // Uma segunda passagem de baixo para cima corrige empilhamentos duplos.
        //
        // Limitação intencional: se o espaço total na tela for insuficiente
        // para todas as bolhas, as últimas podem sair parcialmente pela borda
        // inferior — isso é preferível a sobrepô-las.

        layouts.sortBy { it.bubbleTop }

        repeat(2) { // duas passagens para resolver casos encadeados
            for (i in 1 until layouts.size) {
                val curr = layouts[i]
                for (j in 0 until i) {
                    val prev = layouts[j]
                    // Verifica sobreposição horizontal (eixo X)
                    val overlapX = curr.bubbleLeft < prev.bubbleLeft + prev.boxW &&
                            curr.bubbleLeft + curr.boxW > prev.bubbleLeft
                    if (!overlapX) continue

                    // Verifica sobreposição vertical (eixo Y)
                    val overlapY = curr.bubbleTop < prev.bubbleTop + prev.boxH + bubbleGap &&
                            curr.bubbleTop + curr.boxH > prev.bubbleTop
                    if (!overlapY) continue

                    // Colisão detectada: empurra curr para baixo do prev
                    curr.bubbleTop = prev.bubbleTop + prev.boxH + bubbleGap

                    // Se saiu da tela por baixo, tenta colocar acima do prev
                    if (curr.bubbleTop + curr.boxH > screenHeight) {
                        val above = prev.bubbleTop - curr.boxH - bubbleGap
                        if (above >= 0f) curr.bubbleTop = above
                        // Se também não couber acima, mantém posição empurrada
                        // (parcialmente fora é melhor que sobreposto)
                    }
                }
            }
        }

        // ── 3. Desenha tudo com posições corrigidas ───────────────────────
        layouts.forEach { bl ->
            val idx    = bl.idx
            val boxRect = RectF(bl.bubbleLeft, bl.bubbleTop, bl.bubbleLeft + bl.boxW, bl.bubbleTop + bl.boxH)
            hitBoxes.add(BubbleHitBox(boxRect, idx))

            canvas.drawRect(bl.srcLeft, bl.srcTop, bl.srcRight, bl.srcBottom, coverPaint)
            if (idx == highlightedIndex) canvas.drawRoundRect(boxRect, cornerR, cornerR, highlightPaint)
            canvas.drawRoundRect(boxRect, cornerR, cornerR, bubblePaint)

            val stroke = when {
                idx in editedIndices -> editedStrokePaint
                readOnly             -> bubbleStrokeROPaint
                else                 -> bubbleStrokePaint
            }
            canvas.drawRoundRect(boxRect, cornerR, cornerR, stroke)

            bl.lines.forEachIndexed { i, line ->
                canvas.drawText(line, bl.bubbleLeft + padH,
                    bl.bubbleTop + padV + bl.lineH * i + bl.paint.textSize, bl.paint)
            }

            if (!readOnly) {
                canvas.drawText(if (idx in editedIndices) "✏️" else "📋",
                    bl.bubbleLeft + bl.boxW - padH * 2.5f,
                    bl.bubbleTop + padV + bl.paint.textSize, hintPaint)
            }
        }

        if (!readOnly) {
            val hint = "Toque para copiar/editar • Fora para fechar"
            canvas.drawText(hint, (width - hintPaint.measureText(hint)) / 2f,
                height - 20f * density, hintPaint)
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>(); var current = ""
        text.split(" ").forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) current = candidate
            else { if (current.isNotEmpty()) lines.add(current); current = word }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines.ifEmpty { listOf(text) }
    }
}

// ─────────────────────────────────────────────────────
// MENU GRADE 2×2
// ─────────────────────────────────────────────────────

@SuppressLint("ClickableViewAccessibility")
class CopyMenuView(
    context: Context,
    private val original: String,
    private val translated: String,
    private val onCopyOriginal: () -> Unit,
    private val onCopyTranslation: () -> Unit,
    private val onEditOriginal: () -> Unit,
    private val onEditTranslation: () -> Unit,
    private val onDismiss: () -> Unit
) : View(context) {

    private val density    = context.resources.displayMetrics.density
    private val edgePad    = 12f * density
    private val gap        = 8f  * density
    private val cellH      = 90f * density
    private val menuW      = 290f * density
    private val cornerR    = 14f * density
    private val cellCorner = 10f * density
    private val cellW      get() = (menuW - edgePad * 2 - gap) / 2f
    private val totalH     get() = edgePad + cellH + gap + cellH + edgePad

    private val colorCopyEN = Color.argb(200, 30, 50, 110)
    private val colorEditEN = Color.argb(200, 50, 30, 110)
    private val colorCopyPT = Color.argb(200, 20, 90, 50)
    private val colorEditPT = Color.argb(200, 90, 60, 20)

    private val bgPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(252, 18, 18, 38); style = Paint.Style.FILL }
    private val borderPaint= Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 140, 140, 220); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val cellPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255); style = Paint.Style.FILL }
    private val iconPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f * density; textAlign = Paint.Align.CENTER }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 11f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    private val subPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 210, 210, 255); textSize = 9.5f * density; textAlign = Paint.Align.CENTER
    }

    private data class Cell(var rect: RectF, val color: Int, val icon: String,
                            val line1: String, val line2: String, val action: () -> Unit)

    private val cells = mutableListOf<Cell>()
    private var hoveredCell = -1

    init { buildCells() }

    private fun buildCells() {
        cells.clear()
        val x0 = edgePad; val x1 = edgePad + cellW + gap
        val y0 = edgePad; val y1 = edgePad + cellH + gap
        cells.add(Cell(RectF(x0, y0, x0+cellW, y0+cellH), colorCopyEN, "📋", "Copiar Original",  "(EN)", onCopyOriginal))
        cells.add(Cell(RectF(x1, y0, x1+cellW, y0+cellH), colorEditEN, "✏️", "Editar Original",  "(EN)", onEditOriginal))
        cells.add(Cell(RectF(x0, y1, x0+cellW, y1+cellH), colorCopyPT, "🌐", "Copiar Tradução",  "(PT)", onCopyTranslation))
        cells.add(Cell(RectF(x1, y1, x1+cellW, y1+cellH), colorEditPT, "✏️", "Editar Tradução",  "(PT)", onEditTranslation))
    }

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(menuW.toInt(), totalH.toInt()) }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w,h,ow,oh); buildCells() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRoundRect(RectF(0f,0f,w,h), cornerR, cornerR, bgPaint)
        canvas.drawRoundRect(RectF(0f,0f,w,h), cornerR, cornerR, borderPaint)
        cells.forEachIndexed { idx, cell ->
            cellPaint.color = cell.color
            canvas.drawRoundRect(cell.rect, cellCorner, cellCorner, cellPaint)
            if (idx == hoveredCell) canvas.drawRoundRect(cell.rect, cellCorner, cellCorner, hoverPaint)
            borderPaint.alpha = 60; canvas.drawRoundRect(cell.rect, cellCorner, cellCorner, borderPaint); borderPaint.alpha = 120
            val cx = cell.rect.centerX(); val cy = cell.rect.centerY()
            canvas.drawText(cell.icon,  cx, cy - 14f*density, iconPaint)
            canvas.drawText(cell.line1, cx, cy + 12f*density, labelPaint)
            canvas.drawText(cell.line2, cx, cy + 24f*density, subPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        return when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                hoveredCell = cells.indexOfFirst { it.rect.contains(x,y) }; invalidate(); true
            }
            MotionEvent.ACTION_UP -> {
                val hit = cells.indexOfFirst { it.rect.contains(x,y) }
                hoveredCell = -1; invalidate()
                if (hit >= 0) cells[hit].action() else onDismiss(); true
            }
            MotionEvent.ACTION_CANCEL -> { hoveredCell = -1; invalidate(); true }
            else -> false
        }
    }
}