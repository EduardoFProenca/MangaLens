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
        Settings.Secure.getInt(context.contentResolver,
            "accessibility_display_magnification_enabled") == 1
    } catch (e: Exception) { false }

    // ─────────────────────────────────────────────
    // FILTRO
    // ─────────────────────────────────────────────

    private fun filterAndSort(
        results: List<TextResult>, screenHeight: Int, filterSystemUi: Boolean
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
        } else results.filter { r -> r.originalText.trim().length >= 2 }
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

        val filtered = filterAndSort(results, screenHeight, filterSystemUi)
        if (filtered.isEmpty()) {
            Toast.makeText(context, "🔍 Nenhum texto encontrado", Toast.LENGTH_SHORT).show()
            return
        }

        val mutable = filtered.map { it.copy() }.toMutableList()

        val themedCtx = ContextThemeWrapper(context, R.style.Theme_MangaLens)
        val view = LayoutInflater.from(themedCtx).inflate(R.layout.layout_game_overlay, null)

        val showSafeZone = filterSystemUi && isSystemZoomActive(context)
        view.findViewById<View>(R.id.clockSafeZone).visibility =
            if (showSafeZone) View.VISIBLE else View.GONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        var currentIndex = 0

        // renderCurrent é chamada também após edições para redesenhar
        fun renderCurrent() {
            val result  = mutable[currentIndex]
            val hasNext = currentIndex < mutable.lastIndex

            if (gameModeEnabled) {
                setupGameMode(
                    view, result, hasNext, context, windowManager,
                    onNext   = { currentIndex++; renderCurrent() },
                    onEditOriginal = { newOriginal ->
                        // Usuário corrigiu o EN → dispara nova tradução
                        mutable[currentIndex] = mutable[currentIndex].copy(originalText = newOriginal)
                        retranslateAndRender(context, windowManager, mutable, currentIndex) {
                            renderCurrent()
                        }
                    }
                )
            } else {
                setupTranslationMode(
                    view, result, hasNext, context, windowManager,
                    onNext   = { currentIndex++; renderCurrent() },
                    onEditOriginal = { newOriginal ->
                        mutable[currentIndex] = mutable[currentIndex].copy(originalText = newOriginal)
                        retranslateAndRender(context, windowManager, mutable, currentIndex) {
                            renderCurrent()
                        }
                    },
                    onEditTranslation = { newPt ->
                        mutable[currentIndex] = mutable[currentIndex].copy(translatedText = newPt)
                        renderCurrent()
                    }
                )
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

        windowManager.addView(view, params)
        overlayView = view
    }

    // ─────────────────────────────────────────────
    // RE-TRADUÇÃO APÓS EDIÇÃO DO ORIGINAL
    // ─────────────────────────────────────────────

    /**
     * Quando o usuário corrige o texto EN, re-executa a tradução
     * e atualiza o item na lista mutável antes de chamar [onDone].
     */
    private fun retranslateAndRender(
        context: Context,
        windowManager: WindowManager,
        mutable: MutableList<TextResult>,
        index: Int,
        onDone: () -> Unit
    ) {
        val result = mutable[index]
        Toast.makeText(context, "🔄 Traduzindo correção...", Toast.LENGTH_SHORT).show()

        OcrProcessor.retranslate(result.originalText) { translated ->
            mutable[index] = result.copy(translatedText = translated)
            onDone()
        }
    }

    // ─────────────────────────────────────────────
    // MODO TRADUÇÃO (painel inferior)
    // ─────────────────────────────────────────────

    private fun setupTranslationMode(
        view: View, result: TextResult, hasNext: Boolean,
        context: Context, windowManager: WindowManager,
        onNext: () -> Unit,
        onEditOriginal: (String) -> Unit,
        onEditTranslation: (String) -> Unit
    ) {
        view.findViewById<LinearLayout>(R.id.containerTranslation).visibility = View.VISIBLE
        view.findViewById<LinearLayout>(R.id.containerGame).visibility        = View.GONE
        view.findViewById<TextView>(R.id.btnSkip).visibility                  = View.GONE

        val tvOriginal    = view.findViewById<TextView>(R.id.tvOriginal)
        val tvTranslation = view.findViewById<TextView>(R.id.tvTranslation)

        tvOriginal.apply {
            text       = "🇬🇧 ${result.originalText}"
            visibility = View.VISIBLE
        }
        tvTranslation.text = "🇧🇷 ${result.translatedText}"

        setupCopyButtons(view, context, result.originalText, result.translatedText)

        // ── Botão ✏️ EN — corrige o original e re-traduz ──────────────────
        view.findViewById<TextView>(R.id.btnEditOriginal).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                OverlayEditor.show(
                    context     = context,
                    windowManager = windowManager,
                    title       = "✏️ Corrigir texto original (EN)",
                    initialText = result.originalText,
                    hint        = "Corrija o que o OCR leu errado...",
                    gravityY    = (220 * context.resources.displayMetrics.density).toInt(),
                    onConfirm   = { newText ->
                        tvOriginal.text = "🇬🇧 $newText"
                        onEditOriginal(newText)
                    }
                )
            }
        }

        // ── Botão ✏️ PT — corrige a tradução diretamente ──────────────────
        view.findViewById<TextView>(R.id.btnEditTranslation).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                OverlayEditor.show(
                    context     = context,
                    windowManager = windowManager,
                    title       = "✏️ Corrigir tradução (PT)",
                    initialText = result.translatedText,
                    gravityY    = (220 * context.resources.displayMetrics.density).toInt(),
                    onConfirm   = { newText ->
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
        view: View, result: TextResult, hasNext: Boolean,
        context: Context, windowManager: WindowManager,
        onNext: () -> Unit,
        onEditOriginal: (String) -> Unit
    ) {
        view.findViewById<LinearLayout>(R.id.containerTranslation).visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.containerGame).visibility        = View.VISIBLE
        hideCopyButtons(view)

        // Oculta botões de edição de PT no modo game (não se aplica)
        view.findViewById<TextView>(R.id.btnEditTranslation).visibility = View.GONE

        val tvInstruction   = view.findViewById<TextView>(R.id.tvInstruction)
        val chipGroupAnswer = view.findViewById<ChipGroup>(R.id.chipGroupAnswer)
        val chipGroupWords  = view.findViewById<ChipGroup>(R.id.chipGroupWords)
        val tvResult        = view.findViewById<TextView>(R.id.tvResult)
        val btnNext         = view.findViewById<TextView>(R.id.btnNext)
        val btnSkip         = view.findViewById<TextView>(R.id.btnSkip)

        chipGroupAnswer.removeAllViews(); chipGroupWords.removeAllViews()
        tvResult.visibility = View.GONE; btnNext.visibility = View.GONE
        btnSkip.visibility  = View.VISIBLE

        val originalHint   = result.originalText
        val sentenceToSort = result.translatedText
        tvInstruction.text = "🇬🇧 $originalHint\n\nMonte a tradução em português:"

        // ── Botão ✏️ EN no modo game — corrige o original e recria o jogo ─
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
                        // Re-traduz e recria todo o mini-game com as novas peças
                        onEditOriginal(newText)
                        Toast.makeText(context,
                            "🔄 Recriando mini-game com o texto corrigido...",
                            Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        // ── Botão ✏️ Frase PT — edita a frase que o usuário vai montar ───
        view.findViewById<TextView>(R.id.btnEditGameSentence).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                OverlayEditor.show(
                    context       = context,
                    windowManager = windowManager,
                    title         = "✏️ Corrigir frase do jogo (PT)",
                    initialText   = sentenceToSort,
                    hint          = "Corrija a tradução para montar o jogo...",
                    gravityY      = (220 * context.resources.displayMetrics.density).toInt(),
                    onConfirm     = { newPt ->
                        // Reconstrói os chips com a frase corrigida
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

        if (words.size <= 1) {
            tvResult.text       = "🇧🇷 $sentenceToSort"
            tvResult.setTextColor(Color.WHITE)
            tvResult.visibility = View.VISIBLE
            btnSkip.visibility  = View.GONE
            setupCopyButtons(view, context, originalHint, sentenceToSort)
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
    // CONSTRUÇÃO / RECONSTRUÇÃO DOS CHIPS
    // ─────────────────────────────────────────────

    /** Constrói os chips para uma frase. Chamado na inicialização e após edição. */
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
        tvInstruction.text  = "🇬🇧 $originalHint\n\nMonte a tradução em português:"

        val words     = sentence.split(" ").filter { it.isNotBlank() }
        val available = words.shuffled().toMutableList()
        val selected  = mutableListOf<String>()
        var done      = false

        var refreshW: () -> Unit = {}
        var refreshA: () -> Unit = {}

        refreshW = {
            chipGroupWords.removeAllViews()
            if (!done) available.forEach { word ->
                chipGroupWords.addView(buildWordChip(context, word) {
                    selected.add(word); available.remove(word)
                    refreshW(); refreshA()
                    checkAnswer(selected, words, tvResult, sentence,
                        view, hasNext, context, onNext) {
                        done = true
                        setupCopyButtons(view, context, originalHint, sentence)
                    }
                })
            }
        }

        refreshA = {
            chipGroupAnswer.removeAllViews()
            selected.toList().forEach { word ->
                chipGroupAnswer.addView(buildAnswerChip(context, word, !done) {
                    if (!done) {
                        selected.remove(word); available.add(word)
                        tvResult.visibility = View.GONE
                        btnNext.visibility  = View.GONE
                        btnSkip.visibility  = View.VISIBLE
                        hideCopyButtons(view)
                        refreshW(); refreshA()
                    }
                })
            }
        }

        refreshW()

        // Reconfigura o botão pular com a nova frase
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

    /** Reconstrói o jogo com uma frase PT editada pelo usuário. */
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
        view: View, context: Context, original: String, translated: String
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

    private fun buildAnswerChip(ctx: Context, word: String, enabled: Boolean, onClick: () -> Unit) =
        Chip(ctx).apply {
            text = word; isClickable = enabled
            setChipBackgroundColorResource(android.R.color.holo_blue_light)
            setTextColor(Color.WHITE); setOnClickListener { onClick() }
        }

    private fun buildWordChip(ctx: Context, word: String, onClick: () -> Unit) =
        Chip(ctx).apply { text = word; isClickable = true; setOnClickListener { onClick() } }

    // ─────────────────────────────────────────────
    // VERIFICAÇÃO DE RESPOSTA
    // ─────────────────────────────────────────────

    private fun checkAnswer(
        selected: List<String>, correct: List<String>,
        tvResult: TextView, translation: String,
        view: View, hasNext: Boolean, context: Context,
        onNext: () -> Unit, onCorrect: () -> Unit
    ) {
        when {
            selected == correct -> {
                onCorrect()
                tvResult.text = "✅ Correto! 🇧🇷 $translation"
                tvResult.setTextColor(Color.GREEN); tvResult.visibility = View.VISIBLE
                view.findViewById<TextView>(R.id.btnSkip).visibility = View.GONE
                setupNextButton(view, hasNext, onNext)
            }
            selected.size == correct.size -> {
                tvResult.text = "❌ Quase! Toque nas palavras para reorganizar."
                tvResult.setTextColor(Color.RED); tvResult.visibility = View.VISIBLE
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