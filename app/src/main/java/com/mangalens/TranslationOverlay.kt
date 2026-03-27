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
        // Dimensões reais do bitmap que o OCR processou.
        // Necessário para converter coordenadas do OCR → coordenadas de tela.
        bitmapWidth: Int  = screenWidth,
        bitmapHeight: Int = screenHeight
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
            onDismiss     = { dismiss(windowManager) },
            onCopyMenu    = { idx, original, translated ->
                showCopyMenu(context, windowManager, idx, original, translated, mutableResults)
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
    // MENU DE CÓPIA + EDIÇÃO
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
            context    = context,
            original   = original,
            translated = translated,
            onCopy     = { text, label ->
                copyToClipboard(context, text, label)
                dismissCopyMenu(windowManager)
            },
            onEdit = {
                dismissCopyMenu(windowManager)
                showEditDialog(
                    context       = context,
                    windowManager = windowManager,
                    currentText   = translated,
                    onConfirm     = { newText ->
                        mutableResults[resultIndex] = mutableResults[resultIndex]
                            .copy(translatedText = newText)
                        (overlayView as? TranslationDrawView)?.apply {
                            markEdited(resultIndex)
                            invalidate()
                        }
                    }
                )
            },
            onDismiss = { dismissCopyMenu(windowManager) }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        windowManager.addView(menuView, params)
        copyMenuView = menuView
    }

    private fun dismissCopyMenu(windowManager: WindowManager) {
        copyMenuView?.let { runCatching { windowManager.removeView(it) } }
        copyMenuView = null
    }

    // ─────────────────────────────────────────────
    // DIALOG DE EDIÇÃO (focável para teclado)
    // ─────────────────────────────────────────────

    private fun showEditDialog(
        context: Context,
        windowManager: WindowManager,
        currentText: String,
        onConfirm: (String) -> Unit
    ) {
        dismissEditDialog(windowManager)
        val density = context.resources.displayMetrics.density

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16*density).toInt(), (16*density).toInt(),
                (16*density).toInt(), (16*density).toInt()
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(245, 25, 25, 45))
                cornerRadius = 16f * density
            }
        }

        container.addView(TextView(context).apply {
            text     = "✏️  Corrigir tradução"
            textSize = 14f
            setTextColor(Color.argb(200, 180, 180, 255))
            setPadding(0, 0, 0, (10*density).toInt())
        })

        val editText = EditText(context).apply {
            setText(currentText)
            textSize     = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint         = "Digite a tradução corrigida..."
            background   = null
            setBackgroundColor(Color.argb(80, 100, 100, 180))
            setPadding((12*density).toInt(), (10*density).toInt(),
                (12*density).toInt(), (10*density).toInt())
            maxLines     = 5
            isSingleLine = false
            setSelection(currentText.length)
        }
        container.addView(editText)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.END
            setPadding(0, (12*density).toInt(), 0, 0)
        }
        btnRow.addView(TextView(context).apply {
            text = "Cancelar"; textSize = 13f
            setTextColor(Color.argb(180, 180, 180, 180))
            setPadding((16*density).toInt(), (10*density).toInt(),
                (16*density).toInt(), (10*density).toInt())
            setOnClickListener { dismissEditDialog(windowManager) }
        })
        btnRow.addView(TextView(context).apply {
            text = "✓  Confirmar"; textSize = 13f
            setTextColor(Color.argb(255, 130, 200, 130))
            setPadding((16*density).toInt(), (10*density).toInt(),
                (16*density).toInt(), (10*density).toInt())
            setOnClickListener {
                val t = editText.text.toString().trim()
                if (t.isNotEmpty()) {
                    dismissEditDialog(windowManager)
                    onConfirm(t)
                    Toast.makeText(context, "✅ Texto corrigido", Toast.LENGTH_SHORT).show()
                }
            }
        })
        container.addView(btnRow)

        val params = WindowManager.LayoutParams(
            (300*density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity       = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        windowManager.addView(container, params)
        editDialogView = container

        Handler(Looper.getMainLooper()).postDelayed({
            editText.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    private fun dismissEditDialog(windowManager: WindowManager) {
        editDialogView?.let { v ->
            runCatching {
                val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
            }
            runCatching { windowManager.removeView(v) }
        }
        editDialogView = null
    }

    // ─────────────────────────────────────────────
    // HIDE / RESTORE / DISMISS
    // ─────────────────────────────────────────────

    fun hide() {
        overlayView?.visibility    = View.INVISIBLE
        copyMenuView?.visibility   = View.INVISIBLE
        editDialogView?.visibility = View.INVISIBLE
    }

    fun restore() {
        overlayView?.visibility    = View.VISIBLE
        copyMenuView?.visibility   = View.VISIBLE
        editDialogView?.visibility = View.VISIBLE
    }

    fun dismiss(windowManager: WindowManager) {
        dismissEditDialog(windowManager)
        dismissCopyMenu(windowManager)
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView      = null
        windowManagerRef = null
    }

    fun isVisible(): Boolean = overlayView?.visibility == View.VISIBLE

    fun copyToClipboard(context: Context, text: String, label: String = "MangaLens") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "📋 Copiado: \"${text.take(40)}\"", Toast.LENGTH_SHORT).show()
    }
}

// ─────────────────────────────────────────────────────
// VIEW DE DESENHO — ANCORAGEM PRECISA AO TEXTO ORIGINAL
// ─────────────────────────────────────────────────────

@SuppressLint("ClickableViewAccessibility")
class TranslationDrawView(
    context: Context,
    private val results: MutableList<TextResult>,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val cropRect: Rect?,
    // Dimensões do bitmap processado pelo OCR.
    // Podem diferir das dimensões da tela quando há escalonamento.
    private val bitmapWidth: Int,
    private val bitmapHeight: Int,
    private val onDismiss: () -> Unit,
    private val onCopyMenu: (index: Int, original: String, translated: String) -> Unit
) : View(context) {

    private val density = context.resources.displayMetrics.density

    // Fatores de escala: converte coordenadas do bitmap → coordenadas da tela
    // Quando cropRect≠null, o bitmap é o recorte; as coordenadas são relativas a ele.
    // O offsetX/Y adiciona a posição do recorte na tela.
    private val scaleX: Float get() {
        val bmpW = if (bitmapWidth > 0) bitmapWidth else screenWidth
        val displayW = cropRect?.width() ?: screenWidth
        return displayW.toFloat() / bmpW.toFloat()
    }
    private val scaleY: Float get() {
        val bmpH = if (bitmapHeight > 0) bitmapHeight else screenHeight
        val displayH = cropRect?.height() ?: screenHeight
        return displayH.toFloat() / bmpH.toFloat()
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 248); style = Paint.Style.FILL
    }
    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 80, 80, 200); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val editedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 50, 180, 80); style = Paint.Style.STROKE; strokeWidth = 2.5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.argb(255, 15, 15, 70)
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val editedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.argb(255, 10, 80, 20)
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.argb(130, 255, 255, 255)
        textSize = 10f * density
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }
    // Sombra sutil atrás do texto original para indicar que está coberto
    private val coverPaint = Paint().apply {
        color = Color.argb(12, 0, 0, 0); style = Paint.Style.FILL
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 80, 80, 220); style = Paint.Style.FILL
    }

    private val padH    = 8f  * density
    private val padV    = 5f  * density
    private val cornerR = 7f  * density

    private data class BubbleHitBox(val rect: RectF, val index: Int)
    private val hitBoxes = mutableListOf<BubbleHitBox>()
    private var highlightedIndex = -1
    private var longPressRunnable: Runnable? = null
    private val editedIndices = mutableSetOf<Int>()

    fun markEdited(index: Int) { editedIndices.add(index) }

    init {
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val idx = hitBoxes.indexOfFirst { it.rect.contains(event.x, event.y) }
                    if (idx >= 0) {
                        highlightedIndex = hitBoxes[idx].index
                        invalidate()
                        longPressRunnable = Runnable {
                            TranslationOverlay.copyToClipboard(
                                context, results[hitBoxes[idx].index].originalText, "Original EN"
                            )
                            highlightedIndex = -1; invalidate()
                        }.also { postDelayed(it, 500L) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPressRunnable)
                    val hit = hitBoxes.firstOrNull { it.rect.contains(event.x, event.y) }
                    if (hit != null && hit.index == highlightedIndex) {
                        val r = results[hit.index]
                        onCopyMenu(hit.index, r.originalText, r.translatedText)
                    } else if (hit == null) {
                        onDismiss()
                    }
                    highlightedIndex = -1; invalidate(); true
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPressRunnable)
                    highlightedIndex = -1; invalidate(); true
                }
                else -> false
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitBoxes.clear()

        val offsetX = cropRect?.left?.toFloat() ?: 0f
        val offsetY = cropRect?.top?.toFloat()  ?: 0f

        results.forEachIndexed { idx, result ->
            val box = result.boundingBox ?: return@forEachIndexed
            val translation = result.translatedText.ifBlank { result.originalText }
            val isEdited    = idx in editedIndices

            // ── Converte coordenadas do OCR → coordenadas da tela ─────────
            // As boundingBoxes são em coordenadas do bitmap (possivelmente cropado).
            // scaleX/scaleY ajustam caso o bitmap tenha tamanho diferente da tela.
            // offsetX/Y reposiciona o recorte na tela.
            val screenLeft   = box.left   * scaleX + offsetX
            val screenTop    = box.top    * scaleY + offsetY
            val screenRight  = box.right  * scaleX + offsetX
            val screenBottom = box.bottom * scaleY + offsetY

            // ── Calcula tamanho da caixa de tradução ──────────────────────
            val paint      = if (isEdited) editedTextPaint else textPaint
            // Largura da caixa = largura do boundingBox original (mantém âncora)
            val boxWidth   = (screenRight - screenLeft).coerceAtLeast(60f * density)
            val lines      = wrapText(translation, paint, boxWidth - padH * 2)
            val lineHeight = paint.fontSpacing
            val boxH       = lines.size * lineHeight + padV * 2

            // ── Posicionamento: SEMPRE colado ao texto original ───────────
            // Estratégia: tenta colocar ACIMA do texto (mais natural para mangá).
            // Se não couber acima, coloca ABAIXO.
            // NÃO empurra para outros lugares — fica ancorado ao boundingBox.
            var bubbleTop = screenTop - boxH - 2f * density
            if (bubbleTop < 0f) {
                // Não cabe acima → tenta abaixo
                bubbleTop = screenBottom + 2f * density
            }
            // Garante que não ultrapasse a borda inferior da tela
            if (bubbleTop + boxH > screenHeight) {
                // Último recurso: sobrepõe o texto (dentro do próprio boundingBox)
                bubbleTop = screenTop
            }

            // Alinha horizontalmente com o texto — clamped para não sair da tela
            val bubbleLeft = screenLeft.coerceIn(0f, (screenWidth - boxWidth).coerceAtLeast(0f))

            val boxRect = RectF(bubbleLeft, bubbleTop, bubbleLeft + boxWidth, bubbleTop + boxH)
            hitBoxes.add(BubbleHitBox(boxRect, idx))

            // Sombra muito leve sobre a área do texto original
            canvas.drawRect(screenLeft, screenTop, screenRight, screenBottom, coverPaint)

            if (idx == highlightedIndex) canvas.drawRoundRect(boxRect, cornerR, cornerR, highlightPaint)

            canvas.drawRoundRect(boxRect, cornerR, cornerR, bubblePaint)
            canvas.drawRoundRect(boxRect, cornerR, cornerR,
                if (isEdited) editedStrokePaint else bubbleStrokePaint)

            lines.forEachIndexed { i, line ->
                canvas.drawText(
                    line,
                    bubbleLeft + padH,
                    bubbleTop + padV + lineHeight * i + paint.textSize,
                    paint
                )
            }

            // Ícone de ação no canto
            canvas.drawText(
                if (isEdited) "✏️" else "📋",
                bubbleLeft + boxWidth - padH * 2.5f,
                bubbleTop + padV + paint.textSize,
                hintPaint
            )
        }

        // Dica rodapé
        val hint = "Toque para copiar/editar • Fora para fechar"
        canvas.drawText(
            hint,
            (width - hintPaint.measureText(hint)) / 2f,
            height - 20f * density,
            hintPaint
        )
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines   = mutableListOf<String>()
        var current = ""
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
// MENU FLUTUANTE: Copiar EN | Copiar PT | Editar
// ─────────────────────────────────────────────────────

@SuppressLint("ClickableViewAccessibility")
class CopyMenuView(
    context: Context,
    private val original: String,
    private val translated: String,
    private val onCopy: (text: String, label: String) -> Unit,
    private val onEdit: () -> Unit,
    private val onDismiss: () -> Unit
) : View(context) {

    private val density    = context.resources.displayMetrics.density
    private val pad        = 16f * density
    private val btnH       = 48f * density
    private val menuW      = 270f * density
    private val cornerR    = 12f * density
    private val btnCornerR = 8f  * density

    private val bgPaint         = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(248, 25, 25, 48); style = Paint.Style.FILL }
    private val strokePaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 120, 120, 220); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val btnBgPaint      = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 100, 100, 200); style = Paint.Style.FILL }
    private val btnHoverPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 120, 120, 255); style = Paint.Style.FILL }
    private val editBgPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 50, 140, 80); style = Paint.Style.FILL }
    private val editHoverPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 60, 180, 90); style = Paint.Style.FILL }
    private val labelPaint      = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f * density; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val previewPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 200, 220, 255); textSize = 11f * density }
    private val dividerPaint    = Paint().apply { color = Color.argb(55, 200, 200, 255); strokeWidth = 1f }

    private var rectEN   = RectF(); private var rectPT   = RectF(); private var rectEdit = RectF()
    private var hovered  = -1

    init {
        setOnTouchListener { _, event ->
            val x = event.x; val y = event.y
            val inEN = rectEN.contains(x, y); val inPT = rectPT.contains(x, y)
            val inEdit = rectEdit.contains(x, y)
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    hovered = when { inEN -> 0; inPT -> 1; inEdit -> 2; else -> -1 }; invalidate(); true
                }
                MotionEvent.ACTION_UP -> {
                    hovered = -1; invalidate()
                    when { inEN -> onCopy(original, "Original EN"); inPT -> onCopy(translated, "Tradução PT"); inEdit -> onEdit(); else -> onDismiss() }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { hovered = -1; invalidate(); true }
                else -> false
            }
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(menuW.toInt(), (pad/2 + btnH + pad/4 + btnH + pad/4 + btnH + pad/2).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        canvas.drawRoundRect(RectF(0f, 0f, w, height.toFloat()), cornerR, cornerR, bgPaint)
        canvas.drawRoundRect(RectF(0f, 0f, w, height.toFloat()), cornerR, cornerR, strokePaint)
        val half = pad / 2; var top = half

        rectEN = RectF(half, top, w - half, top + btnH)
        canvas.drawRoundRect(rectEN, btnCornerR, btnCornerR, if (hovered == 0) btnHoverPaint else btnBgPaint)
        canvas.drawText("📋  Copiar Original (EN)", rectEN.left + half, rectEN.top + btnH * 0.42f, labelPaint)
        canvas.drawText(original.take(34) + if (original.length > 34) "…" else "", rectEN.left + half, rectEN.top + btnH * 0.78f, previewPaint)

        top += btnH + pad/4; canvas.drawLine(pad, top, w - pad, top, dividerPaint)

        rectPT = RectF(half, top + pad/4, w - half, top + pad/4 + btnH)
        canvas.drawRoundRect(rectPT, btnCornerR, btnCornerR, if (hovered == 1) btnHoverPaint else btnBgPaint)
        canvas.drawText("🌐  Copiar Tradução (PT)", rectPT.left + half, rectPT.top + btnH * 0.42f, labelPaint)
        canvas.drawText(translated.take(34) + if (translated.length > 34) "…" else "", rectPT.left + half, rectPT.top + btnH * 0.78f, previewPaint)

        top = rectPT.bottom + pad/4; canvas.drawLine(pad, top, w - pad, top, dividerPaint)

        rectEdit = RectF(half, top + pad/4, w - half, top + pad/4 + btnH)
        canvas.drawRoundRect(rectEdit, btnCornerR, btnCornerR, if (hovered == 2) editHoverPaint else editBgPaint)
        canvas.drawText("✏️  Editar tradução", rectEdit.left + half, rectEdit.centerY() + labelPaint.textSize / 3, labelPaint)
    }
}