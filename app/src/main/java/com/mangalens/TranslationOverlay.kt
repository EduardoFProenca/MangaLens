package com.mangalens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.WindowManager
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent

/**
 * Overlay de tradução direta.
 *
 * Quando o mini-game está DESLIGADO, este overlay é exibido em vez do
 * GameOverlayManager. Ele desenha a tradução em português diretamente
 * sobre a posição onde o texto original foi detectado na tela —
 * simulando uma legenda sobreposta ao balão do mangá.
 *
 * Toque em qualquer lugar fecha o overlay.
 */
object TranslationOverlay {

    private var overlayView: View? = null

    fun show(
        context: Context,
        windowManager: WindowManager,
        results: List<TextResult>,
        screenWidth: Int  = context.resources.displayMetrics.widthPixels,
        screenHeight: Int = context.resources.displayMetrics.heightPixels,
        cropRect: Rect?   = null   // se veio de seleção de área, ajusta coordenadas
    ) {
        dismiss(windowManager)
        if (results.isEmpty()) return

        val drawView = TranslationDrawView(context, results, screenWidth, screenHeight, cropRect) {
            dismiss(windowManager)
        }

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

    fun dismiss(windowManager: WindowManager) {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
    }
}

// ─────────────────────────────────────────────────────
// VIEW QUE DESENHA AS TRADUÇÕES
// ─────────────────────────────────────────────────────

@SuppressLint("ClickableViewAccessibility")
class TranslationDrawView(
    context: Context,
    private val results: List<TextResult>,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val cropRect: Rect?,       // offset de seleção de área
    private val onDismiss: () -> Unit
) : View(context) {

    // ── Pincéis ──────────────────────────────────

    // Fundo branco-leitoso atrás de cada tradução (simula balão)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.argb(230, 255, 255, 240)  // branco quase opaco
        style  = Paint.Style.FILL
    }
    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.argb(180, 100, 100, 200)
        style       = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // Texto da tradução em português
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.argb(255, 20, 20, 80)   // azul escuro
        textSize = 14f * context.resources.displayMetrics.density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // Texto de dica "Toque para fechar"
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.argb(180, 255, 255, 255)
        textSize = 12f * context.resources.displayMetrics.density
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }

    // Fundo semi-transparente muito suave atrás da cena toda
    private val scenePaint = Paint().apply {
        color = Color.argb(30, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // Padding interno das caixas de tradução
    private val padH = 10f * context.resources.displayMetrics.density
    private val padV = 6f  * context.resources.displayMetrics.density
    private val cornerR = 8f * context.resources.displayMetrics.density

    init {
        setOnTouchListener { _, _ -> onDismiss(); true }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Leve escurecimento de fundo para destacar as caixas
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scenePaint)

        // Offset: se veio de seleção de área, bounding boxes são relativas ao crop
        val offsetX = cropRect?.left?.toFloat() ?: 0f
        val offsetY = cropRect?.top?.toFloat()  ?: 0f

        results.forEach { result ->
            val box = result.boundingBox ?: return@forEach
            val translation = result.translatedText.ifBlank { result.originalText }

            // Posição absoluta na tela
            val absLeft   = box.left.toFloat()   + offsetX
            val absTop    = box.top.toFloat()     + offsetY
            val absRight  = box.right.toFloat()   + offsetX
            val absBottom = box.bottom.toFloat()  + offsetY

            // Divide o texto em linhas para caber na largura do bounding box
            val maxWidth   = (absRight - absLeft).coerceAtLeast(80f * resources.displayMetrics.density)
            val lines      = wrapText(translation, textPaint, maxWidth - padH * 2)
            val lineHeight = textPaint.fontSpacing
            val totalTextH = lines.size * lineHeight

            // Dimensões da caixa de tradução
            val boxW = maxWidth
            val boxH = totalTextH + padV * 2

            // Posiciona a caixa SOBRE o texto original
            // (centralizado horizontalmente no bounding box original)
            var drawLeft = absLeft
            var drawTop  = absTop - boxH - 4f * resources.displayMetrics.density

            // Se sair do topo da tela, coloca abaixo do original
            if (drawTop < 0f) drawTop = absBottom + 4f * resources.displayMetrics.density

            // Garante que não sai da direita da tela
            if (drawLeft + boxW > screenWidth) drawLeft = screenWidth - boxW

            val boxRect = RectF(drawLeft, drawTop, drawLeft + boxW, drawTop + boxH)

            // Desenha fundo da caixa
            canvas.drawRoundRect(boxRect, cornerR, cornerR, bubblePaint)
            canvas.drawRoundRect(boxRect, cornerR, cornerR, bubbleStrokePaint)

            // Desenha cada linha de texto
            lines.forEachIndexed { i, line ->
                canvas.drawText(
                    line,
                    drawLeft + padH,
                    drawTop + padV + lineHeight * i + textPaint.textSize,
                    textPaint
                )
            }
        }

        // Dica no rodapé
        val hint = "Toque para fechar"
        val hintX = (width - hintPaint.measureText(hint)) / 2f
        val hintY = height - 32f * resources.displayMetrics.density
        canvas.drawText(hint, hintX, hintY, hintPaint)
    }

    // ─────────────────────────────────────────────
    // QUEBRA DE TEXTO
    // ─────────────────────────────────────────────

    /**
     * Divide [text] em linhas que caibam em [maxWidth] pixels
     * de acordo com o [paint] fornecido.
     */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words  = text.split(" ")
        val lines  = mutableListOf<String>()
        var current = ""

        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotEmpty()) lines.add(current)
                current = word
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines.ifEmpty { listOf(text) }
    }
}