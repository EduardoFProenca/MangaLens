package com.mangalens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.graphics.PixelFormat
import android.view.Gravity
import android.widget.Toast

/**
 * Overlay de tradução direta (modo 📖).
 *
 * Interação com cada caixa de tradução:
 *  • Toque curto  → menu com "📋 Copiar Original" e "🌐 Copiar Tradução"
 *  • Toque longo  → copia o texto original imediatamente (atalho rápido)
 *  • Toque fora   → fecha o overlay
 */
object TranslationOverlay {

    private var overlayView: View? = null
    private var windowManagerRef: WindowManager? = null

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

        val drawView = TranslationDrawView(
            context, results, screenWidth, screenHeight, cropRect,
            onDismiss  = { dismiss(windowManager) },
            onCopyMenu = { original, translated ->
                showCopyMenu(context, windowManager, original, translated)
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
    // MENU DE CÓPIA
    // ─────────────────────────────────────────────

    private var copyMenuView: View? = null

    private fun showCopyMenu(
        context: Context,
        windowManager: WindowManager,
        original: String,
        translated: String
    ) {
        dismissCopyMenu(windowManager)

        val density = context.resources.displayMetrics.density
        val menuView = CopyMenuView(
            context      = context,
            original     = original,
            translated   = translated,
            onCopy       = { text, label ->
                copyToClipboard(context, text, label)
                dismissCopyMenu(windowManager)
            },
            onDismiss    = { dismissCopyMenu(windowManager) }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager.addView(menuView, params)
        copyMenuView = menuView
    }

    private fun dismissCopyMenu(windowManager: WindowManager) {
        copyMenuView?.let { runCatching { windowManager.removeView(it) } }
        copyMenuView = null
    }

    // ─────────────────────────────────────────────
    // HIDE / RESTORE (captura contínua)
    // ─────────────────────────────────────────────

    fun hide() {
        overlayView?.visibility  = View.INVISIBLE
        copyMenuView?.visibility = View.INVISIBLE
    }

    fun restore() {
        overlayView?.visibility  = View.VISIBLE
        copyMenuView?.visibility = View.VISIBLE
    }

    fun dismiss(windowManager: WindowManager) {
        dismissCopyMenu(windowManager)
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView      = null
        windowManagerRef = null
    }

    fun isVisible(): Boolean = overlayView?.visibility == View.VISIBLE

    // ─────────────────────────────────────────────
    // UTILITÁRIO DE CLIPBOARD
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
    private val results: List<TextResult>,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val cropRect: Rect?,
    private val onDismiss: () -> Unit,
    private val onCopyMenu: (original: String, translated: String) -> Unit
) : View(context) {

    private val density = context.resources.displayMetrics.density

    // Pincéis
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
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.argb(140, 255, 255, 255)
        textSize = 11f * density
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }
    private val scenePaint = Paint().apply {
        color = Color.argb(25, 0, 0, 0); style = Paint.Style.FILL
    }
    // Destaque de toque
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 80, 80, 220); style = Paint.Style.FILL
    }

    private val padH    = 10f * density
    private val padV    = 6f  * density
    private val cornerR = 8f  * density

    // Guarda as caixas desenhadas para detecção de toque
    private data class BubbleHitBox(
        val rect: RectF, val original: String, val translated: String
    )
    private val hitBoxes = mutableListOf<BubbleHitBox>()
    private var highlightedIndex = -1
    private var longPressRunnable: Runnable? = null

    init {
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val idx = hitBoxes.indexOfFirst { it.rect.contains(event.x, event.y) }
                    if (idx >= 0) {
                        highlightedIndex = idx
                        invalidate()

                        // Toque longo: copia o original imediatamente
                        longPressRunnable = Runnable {
                            TranslationOverlay.copyToClipboard(
                                context, hitBoxes[idx].original, "Original EN"
                            )
                            highlightedIndex = -1
                            invalidate()
                        }.also { postDelayed(it, 500L) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPressRunnable)
                    val idx = hitBoxes.indexOfFirst { it.rect.contains(event.x, event.y) }
                    if (idx >= 0 && idx == highlightedIndex) {
                        // Toque curto numa caixa → abre menu de cópia
                        onCopyMenu(hitBoxes[idx].original, hitBoxes[idx].translated)
                    } else if (idx < 0) {
                        // Toque fora de qualquer caixa → fecha overlay
                        onDismiss()
                    }
                    highlightedIndex = -1
                    invalidate()
                    true
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

            val absLeft   = box.left.toFloat()   + offsetX
            val absTop    = box.top.toFloat()     + offsetY
            val absRight  = box.right.toFloat()   + offsetX
            val absBottom = box.bottom.toFloat()  + offsetY

            val maxWidth   = (absRight - absLeft).coerceAtLeast(80f * density)
            val lines      = wrapText(translation, textPaint, maxWidth - padH * 2)
            val lineHeight = textPaint.fontSpacing
            val boxW       = maxWidth
            val boxH       = lines.size * lineHeight + padV * 2

            var drawLeft = absLeft.coerceAtMost(screenWidth - boxW)
            var drawTop  = absTop - boxH - 4f * density
            if (drawTop < 0f) drawTop = absBottom + 4f * density

            val boxRect = RectF(drawLeft, drawTop, drawLeft + boxW, drawTop + boxH)
            hitBoxes.add(BubbleHitBox(boxRect, result.originalText, translation))

            // Destaque quando pressionado
            if (idx == highlightedIndex) {
                canvas.drawRoundRect(boxRect, cornerR, cornerR, highlightPaint)
            }

            canvas.drawRoundRect(boxRect, cornerR, cornerR, bubblePaint)
            canvas.drawRoundRect(boxRect, cornerR, cornerR, bubbleStrokePaint)

            lines.forEachIndexed { i, line ->
                canvas.drawText(
                    line,
                    drawLeft + padH,
                    drawTop + padV + lineHeight * i + textPaint.textSize,
                    textPaint
                )
            }

            // Ícone de cópia sutil no canto da caixa
            canvas.drawText("📋", drawLeft + boxW - padH * 2.5f, drawTop + padV + textPaint.textSize, hintPaint)
        }

        // Dica no rodapé
        val hint = "Toque no texto para copiar • Fora para fechar"
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
// MENU FLUTUANTE DE CÓPIA
// ─────────────────────────────────────────────────────

@SuppressLint("ClickableViewAccessibility")
class CopyMenuView(
    context: Context,
    private val original: String,
    private val translated: String,
    private val onCopy: (text: String, label: String) -> Unit,
    private val onDismiss: () -> Unit
) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val pad     = 16f * density
    private val btnH    = 48f * density
    private val menuW   = 260f * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 30, 30, 50); style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 120, 120, 220); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val btnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 100, 100, 200); style = Paint.Style.FILL
    }
    private val btnHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 120, 120, 255); style = Paint.Style.FILL
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 200, 200, 255); textSize = 11f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 200, 220, 255); textSize = 11f * density
    }

    private val cornerR = 12f * density
    private val btnCornerR = 8f * density
    private val dividerPaint = Paint().apply {
        color = Color.argb(60, 200, 200, 255); strokeWidth = 1f
    }

    // Rects dos botões para hit-test
    private var btnOriginalRect = RectF()
    private var btnTranslatedRect = RectF()
    private var hoveredBtn = -1  // 0 = original, 1 = tradução

    init {
        setOnTouchListener { _, event ->
            val inOriginal    = btnOriginalRect.contains(event.x, event.y)
            val inTranslated  = btnTranslatedRect.contains(event.x, event.y)

            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    hoveredBtn = when { inOriginal -> 0; inTranslated -> 1; else -> -1 }
                    invalidate()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    hoveredBtn = -1; invalidate()
                    when {
                        inOriginal   -> onCopy(original,   "Original EN")
                        inTranslated -> onCopy(translated, "Tradução PT")
                        else         -> onDismiss()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { hoveredBtn = -1; invalidate(); true }
                else -> false
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalH = pad + btnH + pad / 2 + btnH + pad
        setMeasuredDimension(menuW.toInt(), totalH.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()

        // Fundo do menu
        canvas.drawRoundRect(RectF(0f, 0f, w, h), cornerR, cornerR, bgPaint)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), cornerR, cornerR, strokePaint)

        // Botão 1: Copiar Original
        btnOriginalRect = RectF(pad / 2, pad / 2, w - pad / 2, pad / 2 + btnH)
        canvas.drawRoundRect(btnOriginalRect, btnCornerR, btnCornerR,
            if (hoveredBtn == 0) btnHoverPaint else btnBgPaint)
        canvas.drawText("📋  Copiar Original (EN)", btnOriginalRect.left + pad / 2,
            btnOriginalRect.top + btnH * 0.4f, textPaint)
        // Preview do texto original (truncado)
        canvas.drawText(
            original.take(32) + if (original.length > 32) "…" else "",
            btnOriginalRect.left + pad / 2,
            btnOriginalRect.top + btnH * 0.75f,
            previewPaint
        )

        // Divisória
        val divY = pad / 2 + btnH + pad / 4
        canvas.drawLine(pad, divY, w - pad, divY, dividerPaint)

        // Botão 2: Copiar Tradução
        btnTranslatedRect = RectF(pad / 2, divY + pad / 4, w - pad / 2, divY + pad / 4 + btnH)
        canvas.drawRoundRect(btnTranslatedRect, btnCornerR, btnCornerR,
            if (hoveredBtn == 1) btnHoverPaint else btnBgPaint)
        canvas.drawText("🌐  Copiar Tradução (PT)", btnTranslatedRect.left + pad / 2,
            btnTranslatedRect.top + btnH * 0.4f, textPaint)
        canvas.drawText(
            translated.take(32) + if (translated.length > 32) "…" else "",
            btnTranslatedRect.left + pad / 2,
            btnTranslatedRect.top + btnH * 0.75f,
            previewPaint
        )
    }
}