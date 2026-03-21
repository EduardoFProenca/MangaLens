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

/**
 * Overlay de tradução direta (modo 📖).
 *
 * Interação:
 *  • Toque curto  → menu: Copiar EN | Copiar PT | ✏️ Editar
 *  • Toque longo  → copia o original imediatamente
 *  • Toque fora   → fecha o overlay
 *
 * Edição:
 *  • Abre um EditText focável centralizado na tela
 *  • O overlay principal troca de FLAG_NOT_FOCUSABLE para focável
 *    enquanto o teclado está aberto
 *  • Ao confirmar, substitui o texto traduzido no lugar original
 *  • "Copiar PT" depois copia a versão editada
 */
object TranslationOverlay {

    private var overlayView: View? = null
    private var windowManagerRef: WindowManager? = null
    private var editDialogView: View? = null

    fun show(
        context: Context,
        windowManager: WindowManager,
        results: List<TextResult>,
        screenWidth: Int  = context.resources.displayMetrics.widthPixels,
        screenHeight: Int = context.resources.displayMetrics.heightPixels,
        cropRect: Rect?   = null
    ) {
        dismiss(windowManager)
        if (results.isEmpty()) return

        windowManagerRef = windowManager

        // Cópia mutável para permitir edição sem afetar a lista original
        val mutableResults = results.map { it.copy() }.toMutableList()

        val drawView = TranslationDrawView(
            context       = context,
            results       = mutableResults,
            screenWidth   = screenWidth,
            screenHeight  = screenHeight,
            cropRect      = cropRect,
            onDismiss     = { dismiss(windowManager) },
            onCopyMenu    = { idx, original, translated ->
                showCopyMenu(context, windowManager, idx, original, translated, mutableResults, screenWidth, screenHeight, cropRect)
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

    private var copyMenuView: View? = null

    private fun showCopyMenu(
        context: Context,
        windowManager: WindowManager,
        resultIndex: Int,
        original: String,
        translated: String,
        mutableResults: MutableList<TextResult>,
        screenWidth: Int,
        screenHeight: Int,
        cropRect: Rect?
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
                        // Atualiza o resultado na lista mutável
                        mutableResults[resultIndex] = mutableResults[resultIndex].copy(
                            translatedText = newText
                        )
                        // Redesenha o overlay com o texto corrigido
                        (overlayView as? TranslationDrawView)?.invalidate()
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
    // DIALOG DE EDIÇÃO
    // ─────────────────────────────────────────────

    private fun showEditDialog(
        context: Context,
        windowManager: WindowManager,
        currentText: String,
        onConfirm: (String) -> Unit
    ) {
        dismissEditDialog(windowManager)

        val density = context.resources.displayMetrics.density

        // Container principal
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(), (16 * density).toInt(),
                (16 * density).toInt(), (16 * density).toInt()
            )
            setBackgroundColor(Color.argb(245, 25, 25, 45))
        }

        // Título
        val title = TextView(context).apply {
            text      = "✏️  Corrigir tradução"
            textSize  = 14f
            setTextColor(Color.argb(200, 180, 180, 255))
            setPadding(0, 0, 0, (10 * density).toInt())
        }

        // Campo de edição
        val editText = EditText(context).apply {
            setText(currentText)
            textSize  = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint      = "Digite a tradução corrigida..."
            background = null
            setBackgroundColor(Color.argb(80, 100, 100, 180))
            setPadding(
                (12 * density).toInt(), (10 * density).toInt(),
                (12 * density).toInt(), (10 * density).toInt()
            )
            maxLines  = 5
            isSingleLine = false
            // Posiciona o cursor no final
            setSelection(currentText.length)
        }

        // Botões
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.END
            setPadding(0, (12 * density).toInt(), 0, 0)
        }

        val btnCancel = TextView(context).apply {
            text     = "Cancelar"
            textSize = 13f
            setTextColor(Color.argb(180, 180, 180, 180))
            setPadding((16 * density).toInt(), (10 * density).toInt(),
                (16 * density).toInt(), (10 * density).toInt())
            setOnClickListener { dismissEditDialog(windowManager) }
        }

        val btnConfirm = TextView(context).apply {
            text     = "✓  Confirmar"
            textSize = 13f
            setTextColor(Color.argb(255, 130, 200, 130))
            setPadding((16 * density).toInt(), (10 * density).toInt(),
                (16 * density).toInt(), (10 * density).toInt())
            setOnClickListener {
                val newText = editText.text.toString().trim()
                if (newText.isNotEmpty()) {
                    dismissEditDialog(windowManager)
                    onConfirm(newText)
                    Toast.makeText(context, "✅ Texto corrigido", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnConfirm)

        container.addView(title)
        container.addView(editText)
        container.addView(btnRow)

        // Arredonda o container
        container.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(245, 25, 25, 45))
            cornerRadius = 16f * density
        }

        // ── Parâmetros: FOCUSÁVEL para aceitar teclado ────────────────────
        // Diferença chave: sem FLAG_NOT_FOCUSABLE e com FLAG_WATCH_OUTSIDE_TOUCH
        val params = WindowManager.LayoutParams(
            (300 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,  // fecha ao tocar fora
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity    = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        windowManager.addView(container, params)
        editDialogView = container

        // Abre o teclado automaticamente após um frame
        Handler(Looper.getMainLooper()).postDelayed({
            editText.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    private fun dismissEditDialog(windowManager: WindowManager) {
        editDialogView?.let { view ->
            // Fecha o teclado antes de remover a view
            runCatching {
                val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(view.windowToken, 0)
            }
            runCatching { windowManager.removeView(view) }
        }
        editDialogView = null
    }

    // ─────────────────────────────────────────────
    // HIDE / RESTORE (captura contínua)
    // ─────────────────────────────────────────────

    fun hide() {
        overlayView?.visibility  = View.INVISIBLE
        copyMenuView?.visibility = View.INVISIBLE
        editDialogView?.visibility = View.INVISIBLE
    }

    fun restore() {
        overlayView?.visibility  = View.VISIBLE
        copyMenuView?.visibility = View.VISIBLE
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

    // ─────────────────────────────────────────────
    // CLIPBOARD
    // ─────────────────────────────────────────────

    fun copyToClipboard(context: Context, text: String, label: String = "MangaLens") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "📋 Copiado: \"${text.take(40)}\"", Toast.LENGTH_SHORT).show()
    }
}

// ─────────────────────────────────────────────────────
// VIEW DE DESENHO DAS CAIXAS DE TRADUÇÃO
// ─────────────────────────────────────────────────────

@SuppressLint("ClickableViewAccessibility")
class TranslationDrawView(
    context: Context,
    private val results: MutableList<TextResult>,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val cropRect: Rect?,
    private val onDismiss: () -> Unit,
    // Callback passa o índice do resultado para que a edição saiba qual atualizar
    private val onCopyMenu: (index: Int, original: String, translated: String) -> Unit
) : View(context) {

    private val density = context.resources.displayMetrics.density

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 245); style = Paint.Style.FILL
    }
    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 80, 80, 200); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.argb(255, 15, 15, 70)
        textSize = 14f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val editedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Texto editado aparece em verde escuro para indicar correção manual
        color    = Color.argb(255, 10, 80, 20)
        textSize = 14f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.argb(140, 255, 255, 255)
        textSize = 11f * density
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }
    private val scenePaint = Paint().apply {
        color = Color.argb(25, 0, 0, 0); style = Paint.Style.FILL
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 80, 80, 220); style = Paint.Style.FILL
    }
    // Borda especial para itens editados
    private val editedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 50, 180, 80); style = Paint.Style.STROKE; strokeWidth = 2.5f
    }

    private val padH    = 10f * density
    private val padV    = 6f  * density
    private val cornerR = 8f  * density

    private data class BubbleHitBox(val rect: RectF, val index: Int)
    private val hitBoxes = mutableListOf<BubbleHitBox>()
    private var highlightedIndex = -1
    private var longPressRunnable: Runnable? = null

    // Rastreia quais índices foram editados manualmente
    private val editedIndices = mutableSetOf<Int>()

    fun markEdited(index: Int) {
        editedIndices.add(index)
        invalidate()
    }

    init {
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val idx = hitBoxes.indexOfFirst { it.rect.contains(event.x, event.y) }
                    if (idx >= 0) {
                        highlightedIndex = hitBoxes[idx].index
                        invalidate()
                        longPressRunnable = Runnable {
                            // Toque longo: copia o original diretamente
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
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scenePaint)
        hitBoxes.clear()

        val offsetX = cropRect?.left?.toFloat() ?: 0f
        val offsetY = cropRect?.top?.toFloat()  ?: 0f

        results.forEachIndexed { idx, result ->
            val box         = result.boundingBox ?: return@forEachIndexed
            val translation = result.translatedText.ifBlank { result.originalText }
            val isEdited    = idx in editedIndices

            val absLeft   = box.left.toFloat()   + offsetX
            val absTop    = box.top.toFloat()     + offsetY
            val absRight  = box.right.toFloat()   + offsetX
            val absBottom = box.bottom.toFloat()  + offsetY

            val paint      = if (isEdited) editedTextPaint else textPaint
            val maxWidth   = (absRight - absLeft).coerceAtLeast(80f * density)
            val lines      = wrapText(translation, paint, maxWidth - padH * 2)
            val lineHeight = paint.fontSpacing
            val boxW       = maxWidth
            val boxH       = lines.size * lineHeight + padV * 2

            var drawLeft = absLeft.coerceAtMost(screenWidth - boxW)
            var drawTop  = absTop - boxH - 4f * density
            if (drawTop < 0f) drawTop = absBottom + 4f * density

            val boxRect = RectF(drawLeft, drawTop, drawLeft + boxW, drawTop + boxH)
            hitBoxes.add(BubbleHitBox(boxRect, idx))

            if (idx == highlightedIndex) canvas.drawRoundRect(boxRect, cornerR, cornerR, highlightPaint)

            canvas.drawRoundRect(boxRect, cornerR, cornerR, bubblePaint)
            // Borda verde para itens editados, azul para os originais
            canvas.drawRoundRect(boxRect, cornerR, cornerR,
                if (isEdited) editedStrokePaint else bubbleStrokePaint)

            lines.forEachIndexed { i, line ->
                canvas.drawText(line, drawLeft + padH,
                    drawTop + padV + lineHeight * i + paint.textSize, paint)
            }

            // Ícone: lápis se editado, clipboard se não
            val icon = if (isEdited) "✏️" else "📋"
            canvas.drawText(icon, drawLeft + boxW - padH * 2.5f,
                drawTop + padV + paint.textSize, hintPaint)
        }

        val hint = "Toque para editar/copiar • Fora para fechar"
        canvas.drawText(hint, (width - hintPaint.measureText(hint)) / 2f,
            height - 24f * density, hintPaint)
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

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(248, 25, 25, 48); style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 120, 120, 220); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val btnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 100, 100, 200); style = Paint.Style.FILL
    }
    private val btnHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 120, 120, 255); style = Paint.Style.FILL
    }
    private val editBtnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 50, 140, 80); style = Paint.Style.FILL
    }
    private val editBtnHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 60, 180, 90); style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 200, 220, 255); textSize = 11f * density
    }
    private val dividerPaint = Paint().apply {
        color = Color.argb(55, 200, 200, 255); strokeWidth = 1f
    }

    // Hit rects
    private var rectCopyEN  = RectF()
    private var rectCopyPT  = RectF()
    private var rectEdit    = RectF()
    private var hovered     = -1   // 0=EN, 1=PT, 2=edit

    init {
        setOnTouchListener { _, event ->
            val x = event.x; val y = event.y
            val inEN   = rectCopyEN.contains(x, y)
            val inPT   = rectCopyPT.contains(x, y)
            val inEdit = rectEdit.contains(x, y)

            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> {
                    hovered = when { inEN -> 0; inPT -> 1; inEdit -> 2; else -> -1 }
                    invalidate(); true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    hovered = -1; invalidate()
                    when { inEN -> onCopy(original, "Original EN")
                        inPT -> onCopy(translated, "Tradução PT")
                        inEdit -> onEdit()
                        else -> onDismiss() }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> { hovered = -1; invalidate(); true }
                else -> false
            }
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        // 3 botões + 2 divisórias
        val totalH = pad / 2 + btnH + pad / 4 + btnH + pad / 4 + btnH + pad / 2
        setMeasuredDimension(menuW.toInt(), totalH.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()

        canvas.drawRoundRect(RectF(0f, 0f, w, height.toFloat()), cornerR, cornerR, bgPaint)
        canvas.drawRoundRect(RectF(0f, 0f, w, height.toFloat()), cornerR, cornerR, strokePaint)

        val half = pad / 2
        var top  = half

        // ── Botão Copiar EN ──
        rectCopyEN = RectF(half, top, w - half, top + btnH)
        canvas.drawRoundRect(rectCopyEN, btnCornerR, btnCornerR,
            if (hovered == 0) btnHoverPaint else btnBgPaint)
        canvas.drawText("📋  Copiar Original (EN)", rectCopyEN.left + pad / 2,
            rectCopyEN.top + btnH * 0.42f, labelPaint)
        canvas.drawText(original.take(34) + if (original.length > 34) "…" else "",
            rectCopyEN.left + pad / 2, rectCopyEN.top + btnH * 0.78f, previewPaint)

        top += btnH + pad / 4
        canvas.drawLine(pad, top, w - pad, top, dividerPaint)

        // ── Botão Copiar PT ──
        rectCopyPT = RectF(half, top + pad / 4, w - half, top + pad / 4 + btnH)
        canvas.drawRoundRect(rectCopyPT, btnCornerR, btnCornerR,
            if (hovered == 1) btnHoverPaint else btnBgPaint)
        canvas.drawText("🌐  Copiar Tradução (PT)", rectCopyPT.left + pad / 2,
            rectCopyPT.top + btnH * 0.42f, labelPaint)
        canvas.drawText(translated.take(34) + if (translated.length > 34) "…" else "",
            rectCopyPT.left + pad / 2, rectCopyPT.top + btnH * 0.78f, previewPaint)

        top = rectCopyPT.bottom + pad / 4
        canvas.drawLine(pad, top, w - pad, top, dividerPaint)

        // ── Botão Editar (verde) ──
        rectEdit = RectF(half, top + pad / 4, w - half, top + pad / 4 + btnH)
        canvas.drawRoundRect(rectEdit, btnCornerR, btnCornerR,
            if (hovered == 2) editBtnHoverPaint else editBtnBgPaint)
        canvas.drawText("✏️  Editar tradução", rectEdit.left + pad / 2,
            rectEdit.centerY() + labelPaint.textSize / 3, labelPaint)
    }
}