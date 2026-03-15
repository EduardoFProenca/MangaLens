package com.mangalens

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.*
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

object GameOverlayManager {

    private const val TAG = "MangaLens_Overlay"

    private var overlayView: View? = null
    var gameModeEnabled = true   // pode ser alterado externamente se quiser toggle

    // ─────────────────────────────────────────────
    // ZOOM DE ACESSIBILIDADE
    // ─────────────────────────────────────────────

    private fun isSystemZoomActive(context: Context): Boolean = try {
        Settings.Secure.getInt(
            context.contentResolver,
            "accessibility_display_magnification_enabled"
        ) == 1
    } catch (e: Exception) { false }

    // ─────────────────────────────────────────────
    // FILTRO DE BLOCOS
    //
    // filterSystemUi = true  → modo Tela Cheia:
    //   corta 10% do topo (status bar + relógio) e 8% do rodapé (nav bar)
    //
    // filterSystemUi = false → modo Área ou Tempo Real:
    //   não corta nada — o relógio permanece visível e os blocos
    //   têm bounding box relativo ao recorte já aplicado no bitmap
    // ─────────────────────────────────────────────

    private fun filterAndSort(
        results: List<TextResult>,
        screenHeight: Int,
        filterSystemUi: Boolean
    ): List<TextResult> {
        val filtered = if (filterSystemUi) {
            val topCutoff    = (screenHeight * 0.10f).toInt()
            val bottomCutoff = (screenHeight * 0.92f).toInt()
            results.filter { r ->
                val box = r.boundingBox ?: return@filter false
                box.centerY() in (topCutoff + 1) until bottomCutoff
            }.filter { r ->
                val text = r.originalText.trim()
                text.length >= 3 && !text.matches(Regex("""\d{1,2}:\d{2}.*"""))
            }
        } else {
            // Sem filtro de sistema — apenas descarta vazios
            results.filter { r -> r.originalText.trim().length >= 2 }
        }

        return filtered.sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
    }

    // ─────────────────────────────────────────────
    // ENTRADA PRINCIPAL
    // ─────────────────────────────────────────────

    fun show(
        context: Context,
        windowManager: WindowManager,
        results: List<TextResult>,
        screenHeight: Int    = context.resources.displayMetrics.heightPixels,
        filterSystemUi: Boolean = true   // default conservador para tela cheia
    ) {
        removeOverlay(windowManager)

        val filtered = filterAndSort(results, screenHeight, filterSystemUi)
        if (filtered.isEmpty()) {
            Toast.makeText(context, "🔍 Nenhum texto encontrado", Toast.LENGTH_SHORT).show()
            return
        }

        val themedCtx = ContextThemeWrapper(context, R.style.Theme_MangaLens)
        val view = LayoutInflater.from(themedCtx).inflate(R.layout.layout_game_overlay, null)

        // Safe zone do relógio: só ativa no modo tela cheia + zoom de acessibilidade
        val showSafeZone = filterSystemUi && isSystemZoomActive(context)
        view.findViewById<View>(R.id.clockSafeZone).visibility =
            if (showSafeZone) View.VISIBLE else View.GONE
        if (showSafeZone) Log.d(TAG, "Safe zone ativa (zoom + tela cheia)")

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
                setupGameMode(view, result, hasNext) { currentIndex++; renderCurrent() }
            } else {
                setupTranslationMode(view, result, hasNext) { currentIndex++; renderCurrent() }
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
        view: View, result: TextResult, hasNext: Boolean, onNext: () -> Unit
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
        view: View, result: TextResult, hasNext: Boolean, onNext: () -> Unit
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

        val originalHint   = result.originalText
        val sentenceToSort = result.translatedText

        tvInstruction.text = "🇬🇧 $originalHint\n\nMonte a tradução em português:"

        val words = sentenceToSort.split(" ").filter { it.isNotBlank() }

        if (words.size <= 1) {
            tvResult.text       = "🇧🇷 $sentenceToSort"
            tvResult.setTextColor(Color.WHITE)
            tvResult.visibility = View.VISIBLE
            btnSkip.visibility  = View.GONE
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
                        selected.add(word); available.remove(word)
                        refreshWords(); refreshAnswer()
                        checkAnswer(selected, words, tvResult, sentenceToSort,
                            view, hasNext, onNext) { challengeDone = true }
                    })
                }
            }
        }

        refreshAnswer = {
            chipGroupAnswer.removeAllViews()
            selected.toList().forEach { word ->
                chipGroupAnswer.addView(buildAnswerChip(view.context, word, !challengeDone) {
                    if (!challengeDone) {
                        selected.remove(word); available.add(word)
                        tvResult.visibility = View.GONE
                        btnNext.visibility  = View.GONE
                        btnSkip.visibility  = View.VISIBLE
                        refreshWords(); refreshAnswer()
                    }
                })
            }
        }

        refreshWords()

        btnSkip.setOnClickListener {
            challengeDone       = true
            tvResult.text       = "💡 Resposta: $sentenceToSort"
            tvResult.setTextColor(Color.YELLOW)
            tvResult.visibility = View.VISIBLE
            btnSkip.visibility  = View.GONE
            setupNextButton(view, hasNext, onNext)
            refreshWords()
        }
    }

    // ─────────────────────────────────────────────
    // CHIPS
    // ─────────────────────────────────────────────

    private fun buildAnswerChip(
        context: Context, word: String, enabled: Boolean, onClick: () -> Unit
    ): Chip = Chip(context).apply {
        text        = word
        isClickable = enabled
        setChipBackgroundColorResource(android.R.color.holo_blue_light)
        setTextColor(Color.WHITE)
        setOnClickListener { onClick() }
    }

    private fun buildWordChip(
        context: Context, word: String, onClick: () -> Unit
    ): Chip = Chip(context).apply {
        text        = word
        isClickable = true
        setOnClickListener { onClick() }
    }

    // ─────────────────────────────────────────────
    // VERIFICAÇÃO DE RESPOSTA
    // ─────────────────────────────────────────────

    private fun checkAnswer(
        selected: List<String>, correct: List<String>,
        tvResult: TextView, translation: String,
        view: View, hasNext: Boolean, onNext: () -> Unit,
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
            }
            else -> tvResult.visibility = View.GONE
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