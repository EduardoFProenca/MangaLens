package com.mangalens

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.*
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

object GameOverlayManager {

    private const val TAG = "MangaLens_Overlay"

    private var overlayView: View? = null
    private var gameModeEnabled = true

    // ─────────────────────────────────────────────
    // DETECÇÃO DE ZOOM DO SISTEMA
    // ─────────────────────────────────────────────

    /**
     * Retorna true se o usuário tiver zoom de acessibilidade ativo.
     * Nesse caso exibimos a safe zone do relógio para evitar sobreposição.
     */
    private fun isSystemZoomActive(context: Context): Boolean {
        return try {
            Settings.Secure.getInt(
                context.contentResolver,
                "accessibility_display_magnification_enabled"
            ) == 1
        } catch (e: Exception) {
            false
        }
    }

    // ─────────────────────────────────────────────
    // FILTRO: remove UI do sistema (status bar / nav bar)
    // ─────────────────────────────────────────────

    private fun filterAndSort(
        results: List<TextResult>,
        screenHeight: Int
    ): List<TextResult> {
        // Exclui os 10% superiores (status bar + relógio) e 8% inferiores (nav bar)
        val topCutoff    = (screenHeight * 0.10f).toInt()
        val bottomCutoff = (screenHeight * 0.92f).toInt()

        return results
            .filter { result ->
                val box = result.boundingBox ?: return@filter false
                box.centerY() in (topCutoff + 1) until bottomCutoff
            }
            .filter { result ->
                val text = result.originalText.trim()
                text.length >= 3 && !text.matches(Regex("""\d{1,2}:\d{2}.*"""))
            }
            .sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
    }

    // ─────────────────────────────────────────────
    // ENTRADA PRINCIPAL
    // ─────────────────────────────────────────────

    fun show(
        context: Context,
        windowManager: WindowManager,
        results: List<TextResult>,
        screenHeight: Int = context.resources.displayMetrics.heightPixels
    ) {
        removeOverlay(windowManager)

        val filtered = filterAndSort(results, screenHeight)
        if (filtered.isEmpty()) {
            Toast.makeText(context, "🔍 Nenhum texto de mangá encontrado", Toast.LENGTH_SHORT).show()
            return
        }

        val themedContext = ContextThemeWrapper(context, R.style.Theme_MangaLens)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.layout_game_overlay, null)

        // ── Safe zone do relógio ──────────────────
        // Se zoom estiver ativo, exibe a faixa protetora para que o relógio
        // (deslocado pelo zoom) não sobreponha as palavras do jogo
        val zoomActive = isSystemZoomActive(context)
        view.findViewById<View>(R.id.clockSafeZone).visibility =
            if (zoomActive) View.VISIBLE else View.GONE

        if (zoomActive) {
            Log.d(TAG, "Zoom de acessibilidade detectado — safe zone ativa")
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        var currentIndex = 0

        fun renderCurrent() {
            val result  = filtered[currentIndex]
            val hasNext = currentIndex < filtered.lastIndex

            if (gameModeEnabled) {
                setupGameMode(view, result, hasNext) {
                    currentIndex++
                    renderCurrent()
                }
            } else {
                setupTranslationMode(view, result, hasNext) {
                    currentIndex++
                    renderCurrent()
                }
            }

            view.findViewById<TextView>(R.id.tvProgress).apply {
                text       = "${currentIndex + 1} / ${filtered.size}"
                visibility = if (filtered.size > 1) View.VISIBLE else View.GONE
            }
        }

        renderCurrent()

        view.findViewById<TextView>(R.id.btnClose).setOnClickListener {
            removeOverlay(windowManager)
        }

        windowManager.addView(view, params)
        overlayView = view
    }

    // ─────────────────────────────────────────────
    // MODO TRADUÇÃO
    // ─────────────────────────────────────────────

    private fun setupTranslationMode(
        view: View,
        result: TextResult,
        hasNext: Boolean,
        onNext: () -> Unit
    ) {
        view.findViewById<LinearLayout>(R.id.containerTranslation).visibility = View.VISIBLE
        view.findViewById<LinearLayout>(R.id.containerGame).visibility        = View.GONE
        view.findViewById<TextView>(R.id.btnSkip).visibility                  = View.GONE

        view.findViewById<TextView>(R.id.tvOriginal).apply {
            text       = result.originalText
            visibility = View.VISIBLE
        }
        view.findViewById<TextView>(R.id.tvTranslation).text = result.translatedText

        setupNextButton(view, hasNext, onNext)
    }

    // ─────────────────────────────────────────────
    // MODO GAME
    // ─────────────────────────────────────────────

    private fun setupGameMode(
        view: View,
        result: TextResult,
        hasNext: Boolean,
        onNext: () -> Unit
    ) {
        view.findViewById<LinearLayout>(R.id.containerTranslation).visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.containerGame).visibility        = View.VISIBLE

        val tvInstruction   = view.findViewById<TextView>(R.id.tvInstruction)
        val chipGroupAnswer = view.findViewById<ChipGroup>(R.id.chipGroupAnswer)
        val chipGroupWords  = view.findViewById<ChipGroup>(R.id.chipGroupWords)
        val tvResult        = view.findViewById<TextView>(R.id.tvResult)
        val btnNext         = view.findViewById<TextView>(R.id.btnNext)
        val btnSkip         = view.findViewById<TextView>(R.id.btnSkip)

        chipGroupAnswer.removeAllViews()
        chipGroupWords.removeAllViews()
        tvResult.visibility = View.GONE
        btnNext.visibility  = View.GONE
        btnSkip.visibility  = View.VISIBLE

        // O desafio: ver o original em EN, montar a tradução em PT
        val originalHint   = result.originalText
        val sentenceToSort = result.translatedText

        tvInstruction.text = "🇬🇧 $originalHint\n\nMonte a tradução em português:"

        val words = sentenceToSort.split(" ").filter { it.isNotBlank() }

        // Frase de 1 palavra — exibe direto
        if (words.size <= 1) {
            tvResult.text        = "🇧🇷 $sentenceToSort"
            tvResult.setTextColor(Color.WHITE)
            tvResult.visibility  = View.VISIBLE
            btnSkip.visibility   = View.GONE
            setupNextButton(view, hasNext, onNext)
            return
        }

        val available     = words.shuffled().toMutableList()
        val selected      = mutableListOf<String>()
        var challengeDone = false

        var refreshWords:  () -> Unit = {}
        var refreshAnswer: () -> Unit = {}

        refreshWords = {
            chipGroupWords.removeAllViews()
            if (!challengeDone) {
                available.forEach { word ->
                    chipGroupWords.addView(buildWordChip(view.context, word) {
                        selected.add(word)
                        available.remove(word)
                        refreshWords()
                        refreshAnswer()
                        checkAnswer(
                            selected, words, tvResult, sentenceToSort,
                            view, hasNext, onNext,
                            onCorrect = { challengeDone = true }
                        )
                    })
                }
            }
        }

        refreshAnswer = {
            chipGroupAnswer.removeAllViews()
            selected.toList().forEach { word ->
                chipGroupAnswer.addView(buildAnswerChip(view.context, word, enabled = !challengeDone) {
                    if (!challengeDone) {
                        selected.remove(word)
                        available.add(word)
                        // Bug 3 fix: restaura visibilidade correta ao desfazer
                        tvResult.visibility = View.GONE
                        btnNext.visibility  = View.GONE
                        btnSkip.visibility  = View.VISIBLE
                        refreshWords()
                        refreshAnswer()
                    }
                })
            }
        }

        refreshWords()

        btnSkip.setOnClickListener {
            challengeDone = true
            tvResult.text = "💡 Resposta: $sentenceToSort"
            tvResult.setTextColor(Color.YELLOW)
            tvResult.visibility = View.VISIBLE
            btnSkip.visibility  = View.GONE
            setupNextButton(view, hasNext, onNext)
            refreshWords()   // remove chips restantes (challengeDone = true)
        }
    }

    // ─────────────────────────────────────────────
    // FACTORIES DE CHIP
    // ─────────────────────────────────────────────

    /** Chip azul para a área de resposta */
    private fun buildAnswerChip(
        context: Context,
        word: String,
        enabled: Boolean,
        onClick: () -> Unit
    ): Chip = Chip(context).apply {
        text        = word
        isClickable = enabled
        setChipBackgroundColorResource(android.R.color.holo_blue_light)
        setTextColor(Color.WHITE)
        setOnClickListener { onClick() }
    }

    /** Chip padrão para a área de palavras disponíveis */
    private fun buildWordChip(
        context: Context,
        word: String,
        onClick: () -> Unit
    ): Chip = Chip(context).apply {
        text        = word
        isClickable = true
        setOnClickListener { onClick() }
    }

    // ─────────────────────────────────────────────
    // VERIFICAÇÃO DE RESPOSTA
    // ─────────────────────────────────────────────

    private fun checkAnswer(
        selected: List<String>,
        correct: List<String>,
        tvResult: TextView,
        translation: String,
        view: View,
        hasNext: Boolean,
        onNext: () -> Unit,
        onCorrect: () -> Unit
    ) {
        when {
            selected == correct -> {
                onCorrect()
                tvResult.text = "✅ Correto! 🇧🇷 $translation"
                tvResult.setTextColor(Color.GREEN)
                tvResult.visibility = View.VISIBLE
                view.findViewById<TextView>(R.id.btnSkip).visibility = View.GONE
                setupNextButton(view, hasNext, onNext)
            }
            selected.size == correct.size -> {
                tvResult.text = "❌ Quase! Toque nas palavras para reorganizar."
                tvResult.setTextColor(Color.RED)
                tvResult.visibility = View.VISIBLE
                // btnNext não aparece — usuário ainda pode corrigir ou pular
            }
            else -> {
                tvResult.visibility = View.GONE
            }
        }
    }

    // ─────────────────────────────────────────────
    // BOTÃO PRÓXIMA / CONCLUÍDO
    // ─────────────────────────────────────────────

    private fun setupNextButton(view: View, hasNext: Boolean, onNext: () -> Unit) {
        view.findViewById<TextView>(R.id.btnNext).apply {
            visibility = View.VISIBLE
            text       = if (hasNext) "Próxima frase ▶" else "✓ Concluído"
            setOnClickListener(null)
            setOnClickListener { if (hasNext) onNext() }
        }
    }

    // ─────────────────────────────────────────────
    // UTILITÁRIOS
    // ─────────────────────────────────────────────

    private fun removeOverlay(windowManager: WindowManager) {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
    }
}