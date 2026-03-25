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
    var gameModeEnabled = true

    // ─────────────────────────────────────────────
    // HIDE / RESTORE
    // ─────────────────────────────────────────────

    fun hideForCapture() { overlayView?.visibility = View.INVISIBLE }
    fun restoreForCapture() { overlayView?.visibility = View.VISIBLE }

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
    // FILTRO — dois modos separados
    // ─────────────────────────────────────────────

    /**
     * BUG 1 FIX — CAUSA RAIZ:
     * A versão anterior tinha UMA função filterAndSort usada nos dois modos.
     * Com filterSystemUi=true (modo SINGLE/tela cheia), ela descartava qualquer
     * TextResult onde boundingBox == null via "return@filter false".
     * O OcrProcessor.mergeNearbyBlocks() pode produzir blocos com box=null
     * quando o bloco original não tinha coordenadas.
     * Resultado: todos os blocos eram descartados → mini-game vazio.
     *
     * SOLUÇÃO: duas funções distintas.
     * - filterForGameMode: filtra APENAS por comprimento de texto (não exige box)
     * - filterForTranslationMode: exige box para posicionar as bolhas na tela
     */
    private fun filterForGameMode(results: List<TextResult>): List<TextResult> {
        return results
            .filter { r -> r.originalText.trim().length >= 2 }
            .sortedBy { it.boundingBox?.top ?: 0 }
    }

    private fun filterForTranslationMode(
        results: List<TextResult>,
        screenHeight: Int,
        filterSystemUi: Boolean
    ): List<TextResult> {
        val filtered = if (filterSystemUi) {
            val top = (screenHeight * 0.10f).toInt()
            val bot = (screenHeight * 0.92f).toInt()
            results.filter { r ->
                val box = r.boundingBox ?: return@filter false
                box.centerY() in (top + 1) until bot
            }.filter { r ->
                val t = r.originalText.trim()
                t.length >= 3 && !t.matches(Regex("""\d{1,2}:\d{2}.*"""))
            }
        } else {
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
        screenHeight: Int       = context.resources.displayMetrics.heightPixels,
        filterSystemUi: Boolean = true
    ) {
        removeOverlay(windowManager)

        // BUG 1 FIX: usa filtro específico para cada modo
        val filtered = if (gameModeEnabled) {
            filterForGameMode(results)
        } else {
            filterForTranslationMode(results, screenHeight, filterSystemUi)
        }

        if (filtered.isEmpty()) {
            Toast.makeText(context, "🔍 Nenhum texto encontrado", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "Resultados para exibição: ${filtered.size} itens. Modo: ${if (gameModeEnabled) "GAME" else "TRANSLATION"}")
        filtered.forEachIndexed { i, r ->
            Log.d(TAG, "  [$i] original='${r.originalText.take(40)}' translated='${r.translatedText.take(40)}' box=${r.boundingBox}")
        }

        val mutable = filtered.map { it.copy() }.toMutableList()

        val themedCtx = ContextThemeWrapper(context, R.style.Theme_MangaLens)
        val view = LayoutInflater.from(themedCtx).inflate(R.layout.layout_game_overlay, null)

        val showSafeZone = filterSystemUi && isSystemZoomActive(context)
        view.findViewById<View>(R.id.clockSafeZone).visibility =
            if (showSafeZone) View.VISIBLE else View.GONE

        // FLAG_NOT_TOUCH_MODAL permite que Chips e botões recebam toques.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        var currentIndex = 0

        fun renderCurrent() {
            val result  = mutable[currentIndex]
            val hasNext = currentIndex < mutable.lastIndex

            try {
                if (gameModeEnabled) {
                    setupGameMode(
                        view, result, hasNext, context, windowManager,
                        onNext         = { currentIndex++; renderCurrent() },
                        onEditOriginal = { newOriginal ->
                            mutable[currentIndex] =
                                mutable[currentIndex].copy(originalText = newOriginal)
                            retranslateAndRender(context, mutable, currentIndex) {
                                renderCurrent()
                            }
                        }
                    )
                } else {
                    setupTranslationMode(
                        view, result, hasNext, context, windowManager,
                        onNext            = { currentIndex++; renderCurrent() },
                        onEditOriginal    = { newOriginal ->
                            mutable[currentIndex] =
                                mutable[currentIndex].copy(originalText = newOriginal)
                            retranslateAndRender(context, mutable, currentIndex) {
                                renderCurrent()
                            }
                        },
                        onEditTranslation = { newPt ->
                            mutable[currentIndex] =
                                mutable[currentIndex].copy(translatedText = newPt)
                            renderCurrent()
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao renderizar: ${e.message}", e)
                Toast.makeText(context, "❌ Erro: ${e.message}", Toast.LENGTH_LONG).show()
            }

            view.findViewById<TextView>(R.id.tvProgress).apply {
                text       = "${currentIndex + 1} / ${mutable.size}"
                visibility = if (mutable.size > 1) View.VISIBLE else View.GONE
            }
        }

        renderCurrent()

        view.findViewById<TextView>(R.id.btnClose).setOnClickListener {
            OverlayEditor.dismiss(windowManager)
            removeOverlay(windowManager)
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao adicionar overlay: ${e.message}", e)
            Toast.makeText(context, "❌ Falha ao abrir: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ─────────────────────────────────────────────
    // RE-TRADUÇÃO APÓS EDIÇÃO DO ORIGINAL
    // ─────────────────────────────────────────────

    private fun retranslateAndRender(
        context: Context,
        mutable: MutableList<TextResult>,
        index: Int,
        onDone: () -> Unit
    ) {
        Toast.makeText(context, "🔄 Traduzindo correção...", Toast.LENGTH_SHORT).show()
        OcrProcessor.retranslate(mutable[index].originalText) { translated ->
            mutable[index] = mutable[index].copy(translatedText = translated)
            onDone()
        }
    }

    // ─────────────────────────────────────────────
    // MODO TRADUÇÃO DIRETA
    // ─────────────────────────────────────────────

    private fun setupTranslationMode(
        view: View,
        result: TextResult,
        hasNext: Boolean,
        context: Context,
        windowManager: WindowManager,
        onNext: () -> Unit,
        onEditOriginal: (String) -> Unit,
        onEditTranslation: (String) -> Unit
    ) {
        view.findViewById<LinearLayout>(R.id.containerTranslation).visibility = View.VISIBLE
        view.findViewById<LinearLayout>(R.id.containerGame).visibility        = View.GONE
        view.findViewById<TextView>(R.id.btnSkip).visibility                  = View.GONE
        view.findViewById<TextView>(R.id.btnEditGameSentence).visibility      = View.GONE

        val tvOriginal    = view.findViewById<TextView>(R.id.tvOriginal)
        val tvTranslation = view.findViewById<TextView>(R.id.tvTranslation)

        tvOriginal.apply {
            text       = "🇬🇧 ${result.originalText}"
            visibility = View.VISIBLE
        }
        tvTranslation.text = "🇧🇷 ${result.translatedText}"

        // Botões de cópia sempre visíveis no modo tradução
        setupCopyButtons(view, context, result.originalText, result.translatedText)

        // BUG 3 FIX — CAUSA RAIZ:
        // O problema não era só visibilidade: o btnEditOriginal ficava numa linha
        // abaixo do containerCopyButtons. Quando containerCopyButtons ficava VISIBLE
        // (wrap_content), ele tomava espaço e empurrava a linha dos botões de edição
        // para fora da área visível do painel em telas menores.
        // SOLUÇÃO no XML: botões de edição ficam ACIMA dos botões de cópia.
        // SOLUÇÃO no Kotlin: ambos os botões EN e PT setados explicitamente aqui.
        view.findViewById<TextView>(R.id.btnEditOriginal).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                OverlayEditor.show(
                    context       = context,
                    windowManager = windowManager,
                    title         = "✏️ Corrigir texto original (EN)",
                    initialText   = result.originalText,
                    hint          = "Corrija o que o OCR leu errado...",
                    gravityY      = (220 * context.resources.displayMetrics.density).toInt(),
                    onConfirm     = { newText ->
                        tvOriginal.text = "🇬🇧 $newText"
                        onEditOriginal(newText)
                    }
                )
            }
        }

        view.findViewById<TextView>(R.id.btnEditTranslation).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                OverlayEditor.show(
                    context       = context,
                    windowManager = windowManager,
                    title         = "✏️ Corrigir tradução (PT)",
                    initialText   = result.translatedText,
                    gravityY      = (220 * context.resources.displayMetrics.density).toInt(),
                    onConfirm     = { newText ->
                        tvTranslation.text = "🇧🇷 $newText"
                        setupCopyButtons(view, context, result.originalText, newText)
                        onEditTranslation(newText)
                        Toast.makeText(context, "✅ Tradução corrigida", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        setupNextButton(view, hasNext, onNext)
    }

    // ─────────────────────────────────────────────
    // MODO GAME
    // ─────────────────────────────────────────────

    private fun setupGameMode(
        view: View,
        result: TextResult,
        hasNext: Boolean,
        context: Context,
        windowManager: WindowManager,
        onNext: () -> Unit,
        onEditOriginal: (String) -> Unit
    ) {
        // containerGame = VISIBLE antes de popular os chips (necessário para layout pass)
        view.findViewById<LinearLayout>(R.id.containerTranslation).visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.containerGame).visibility        = View.VISIBLE

        // No game mode, ✏️ PT e ✏️ Frase são os controles de edição — ✏️ tradução não se aplica
        view.findViewById<TextView>(R.id.btnEditTranslation).visibility = View.GONE

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

        val originalHint = result.originalText

        // BUG 1 FIX: fallback quando tradução não está disponível (modelo não baixado).
        // OcrProcessor retorna translatedText == originalText nesses casos.
        val sentenceToSort = result.translatedText
            .takeIf { it.isNotBlank() && it.trim() != originalHint.trim() }
            ?: run {
                if (result.translatedText.isBlank() || result.translatedText.trim() == originalHint.trim()) {
                    Log.w(TAG, "Tradução indisponível para '$originalHint' — usando EN no jogo")
                    Toast.makeText(
                        context,
                        "⚠️ Tradução indisponível — jogo em inglês (EN).",
                        Toast.LENGTH_LONG
                    ).show()
                }
                originalHint
            }

        val sentenceLang = if (sentenceToSort.trim() == originalHint.trim()) "🇬🇧" else "🇧🇷"
        tvInstruction.text = "🇬🇧 $originalHint\n\nMonte a frase $sentenceLang:"

        Log.d(TAG, "Game mode: original='$originalHint' sentence='$sentenceToSort'")

        // BUG 2 FIX — CAUSA RAIZ:
        // setupCopyButtons era chamado APENAS após acerto ou pulo.
        // O usuário que não interage com o jogo nunca via os botões de cópia.
        // SOLUÇÃO: mostrar cópia desde o início do game mode.
        // No game, mostramos EN para copiar e PT quando disponível.
        setupCopyButtons(view, context, originalHint, sentenceToSort)

        // ── ✏️ EN — visível desde o início em ambos os modos ──
        view.findViewById<TextView>(R.id.btnEditOriginal).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                OverlayEditor.show(
                    context       = context,
                    windowManager = windowManager,
                    title         = "✏️ Corrigir texto original (EN)",
                    initialText   = originalHint,
                    hint          = "Corrija o que o OCR leu errado...",
                    gravityY      = (220 * context.resources.displayMetrics.density).toInt(),
                    onConfirm     = { newText ->
                        onEditOriginal(newText)
                        Toast.makeText(context, "🔄 Recriando mini-game...", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        // ── ✏️ Frase do jogo ──
        view.findViewById<TextView>(R.id.btnEditGameSentence).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                OverlayEditor.show(
                    context       = context,
                    windowManager = windowManager,
                    title         = "✏️ Corrigir frase do jogo",
                    initialText   = sentenceToSort,
                    hint          = "Corrija a frase para montar o jogo...",
                    gravityY      = (220 * context.resources.displayMetrics.density).toInt(),
                    onConfirm     = { newPt ->
                        rebuildGameWithSentence(
                            view, newPt, originalHint,
                            chipGroupAnswer, chipGroupWords,
                            tvResult, btnNext, btnSkip,
                            tvInstruction, hasNext, context, onNext
                        )
                        Toast.makeText(context, "🎮 Jogo atualizado!", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        val words = sentenceToSort.split(" ").filter { it.isNotBlank() }
        Log.d(TAG, "Palavras para o jogo (${words.size}): $words")

        if (words.size <= 1) {
            // Frase de uma palavra só — exibe direto sem jogo de montar
            tvResult.text       = "$sentenceLang $sentenceToSort"
            tvResult.setTextColor(Color.WHITE)
            tvResult.visibility = View.VISIBLE
            btnSkip.visibility  = View.GONE
            setupNextButton(view, hasNext, onNext)
            return
        }

        buildGameChips(
            view, sentenceToSort, originalHint,
            chipGroupAnswer, chipGroupWords,
            tvResult, btnNext, btnSkip, tvInstruction,
            hasNext, context, onNext
        )

        btnSkip.setOnClickListener {
            tvResult.text       = "💡 Resposta: $sentenceToSort"
            tvResult.setTextColor(Color.YELLOW)
            tvResult.visibility = View.VISIBLE
            btnSkip.visibility  = View.GONE
            setupCopyButtons(view, context, originalHint, sentenceToSort)
            setupNextButton(view, hasNext, onNext)
            chipGroupWords.removeAllViews()
        }
    }

    // ─────────────────────────────────────────────
    // CONSTRUÇÃO DOS CHIPS
    // ─────────────────────────────────────────────

    private fun buildGameChips(
        view: View,
        sentence: String,
        originalHint: String,
        chipGroupAnswer: ChipGroup,
        chipGroupWords: ChipGroup,
        tvResult: TextView,
        btnNext: TextView,
        btnSkip: TextView,
        tvInstruction: TextView,
        hasNext: Boolean,
        context: Context,
        onNext: () -> Unit
    ) {
        chipGroupAnswer.removeAllViews()
        chipGroupWords.removeAllViews()
        tvResult.visibility = View.GONE
        btnNext.visibility  = View.GONE
        btnSkip.visibility  = View.VISIBLE

        val sentenceLang = if (sentence.trim() == originalHint.trim()) "🇬🇧" else "🇧🇷"
        tvInstruction.text = "🇬🇧 $originalHint\n\nMonte a frase $sentenceLang:"

        val words     = sentence.split(" ").filter { it.isNotBlank() }
        val available = words.shuffled().toMutableList()
        val selected  = mutableListOf<String>()
        var done      = false

        var refreshW: () -> Unit = {}
        var refreshA: () -> Unit = {}

        refreshW = {
            chipGroupWords.removeAllViews()
            if (!done) {
                available.forEach { word ->
                    chipGroupWords.addView(buildWordChip(context, word) {
                        selected.add(word)
                        available.remove(word)
                        refreshW()
                        refreshA()
                        checkAnswer(selected, words, tvResult, sentence, view, hasNext, context, onNext) {
                            done = true
                            setupCopyButtons(view, context, originalHint, sentence)
                        }
                    })
                }
            }
        }

        refreshA = {
            chipGroupAnswer.removeAllViews()
            selected.toList().forEach { word ->
                chipGroupAnswer.addView(buildAnswerChip(context, word, !done) {
                    if (!done) {
                        selected.remove(word)
                        available.add(word)
                        tvResult.visibility = View.GONE
                        btnNext.visibility  = View.GONE
                        btnSkip.visibility  = View.VISIBLE
                        // Mantém cópia visível mesmo ao desfazer uma palavra
                        setupCopyButtons(view, context, originalHint, sentence)
                        refreshW()
                        refreshA()
                    }
                })
            }
        }

        refreshW()

        view.findViewById<TextView>(R.id.btnSkip).setOnClickListener {
            done = true
            tvResult.text       = "💡 Resposta: $sentence"
            tvResult.setTextColor(Color.YELLOW)
            tvResult.visibility = View.VISIBLE
            btnSkip.visibility  = View.GONE
            setupCopyButtons(view, context, originalHint, sentence)
            setupNextButton(view, hasNext, onNext)
            chipGroupWords.removeAllViews()
        }
    }

    // ─────────────────────────────────────────────
    // RECONSTRUÇÃO APÓS EDIÇÃO DA FRASE DO JOGO
    // ─────────────────────────────────────────────

    private fun rebuildGameWithSentence(
        view: View,
        newSentence: String,
        originalHint: String,
        chipGroupAnswer: ChipGroup,
        chipGroupWords: ChipGroup,
        tvResult: TextView,
        btnNext: TextView,
        btnSkip: TextView,
        tvInstruction: TextView,
        hasNext: Boolean,
        context: Context,
        onNext: () -> Unit
    ) {
        if (newSentence.split(" ").filter { it.isNotBlank() }.size <= 1) {
            tvResult.text       = "🇧🇷 $newSentence"
            tvResult.setTextColor(Color.WHITE)
            tvResult.visibility = View.VISIBLE
            btnSkip.visibility  = View.GONE
            chipGroupAnswer.removeAllViews()
            chipGroupWords.removeAllViews()
            setupCopyButtons(view, context, originalHint, newSentence)
            setupNextButton(view, hasNext, onNext)
            return
        }

        buildGameChips(
            view, newSentence, originalHint,
            chipGroupAnswer, chipGroupWords,
            tvResult, btnNext, btnSkip, tvInstruction,
            hasNext, context, onNext
        )
    }

    // ─────────────────────────────────────────────
    // BOTÕES DE CÓPIA
    // ─────────────────────────────────────────────

    private fun setupCopyButtons(
        view: View,
        context: Context,
        original: String,
        translated: String
    ) {
        view.findViewById<LinearLayout>(R.id.containerCopyButtons).visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.btnCopyOriginal).apply {
            visibility = View.VISIBLE
            setOnClickListener { TranslationOverlay.copyToClipboard(context, original, "Original EN") }
        }
        view.findViewById<TextView>(R.id.btnCopyTranslation).apply {
            visibility = View.VISIBLE
            setOnClickListener { TranslationOverlay.copyToClipboard(context, translated, "Tradução PT") }
        }
    }

    private fun hideCopyButtons(view: View) {
        view.findViewById<LinearLayout>(R.id.containerCopyButtons).visibility = View.GONE
    }

    // ─────────────────────────────────────────────
    // CHIPS
    // ─────────────────────────────────────────────

    private fun buildAnswerChip(
        ctx: Context, word: String, enabled: Boolean, onClick: () -> Unit
    ) = Chip(ctx).apply {
        text = word
        isClickable = enabled
        setChipBackgroundColorResource(android.R.color.holo_blue_light)
        setTextColor(Color.WHITE)
        setOnClickListener { onClick() }
    }

    private fun buildWordChip(
        ctx: Context, word: String, onClick: () -> Unit
    ) = Chip(ctx).apply {
        text = word
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
        context: Context,
        onNext: () -> Unit,
        onCorrect: () -> Unit
    ) {
        when {
            selected == correct -> {
                onCorrect()
                tvResult.text = "✅ Correto! $translation"
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
    // BOTÃO PRÓXIMA
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