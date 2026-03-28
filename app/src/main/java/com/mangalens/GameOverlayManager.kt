// app/src/main/java/com/mangalens/GameOverlayManager.kt
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
    private var isRendering = false
    var gameModeEnabled = true

    fun hideForCapture() { overlayView?.visibility = View.INVISIBLE }
    fun restoreForCapture() { overlayView?.visibility = View.VISIBLE }

    private fun isSystemZoomActive(context: Context): Boolean = try {
        Settings.Secure.getInt(context.contentResolver,
            "accessibility_display_magnification_enabled") == 1
    } catch (e: Exception) { false }

    // ─────────────────────────────────────────────
    // FILTROS
    // ─────────────────────────────────────────────

    private fun filterForGameMode(results: List<TextResult>): List<TextResult> =
        results
            .filter { r ->
                val t = r.originalText.trim()
                if (t.length < 2) return@filter false
                if (t.matches(Regex("""\d{1,2}:\d{2}(\s?(AM|PM))?"""))) return@filter false
                if (t.matches(Regex("""\d{1,3}%"""))) return@filter false
                true
            }
            .sortedBy { it.boundingBox?.top ?: 0 }

    private fun filterForTranslationMode(
        results: List<TextResult>,
        screenHeight: Int,
        filterSystemUi: Boolean
    ): List<TextResult> {
        val filtered = if (filterSystemUi) {
            val top = (screenHeight * 0.10f).toInt()
            val bot = (screenHeight * 0.92f).toInt()
            results
                .filter { r ->
                    val box = r.boundingBox ?: return@filter false
                    box.centerY() in (top + 1) until bot
                }
                .filter { r ->
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

        val filtered = if (gameModeEnabled) filterForGameMode(results)
        else filterForTranslationMode(results, screenHeight, filterSystemUi)

        if (filtered.isEmpty()) {
            Toast.makeText(context, "🔍 Nenhum texto encontrado", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "Resultados: ${filtered.size}. Modo: ${if (gameModeEnabled) "GAME" else "TRANSLATION"}")
        filtered.forEachIndexed { i, r ->
            Log.d(TAG, "  [$i] orig='${r.originalText.take(30)}' trad='${r.translatedText.take(30)}'")
        }

        val mutable   = filtered.map { it.copy() }.toMutableList()
        val themedCtx = ContextThemeWrapper(context, R.style.Theme_MangaLens)
        val view      = LayoutInflater.from(themedCtx).inflate(R.layout.layout_game_overlay, null)

        view.findViewById<View>(R.id.clockSafeZone).visibility =
            if (filterSystemUi && isSystemZoomActive(context)) View.VISIBLE else View.GONE

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
            if (isRendering) {
                Log.d(TAG, "Render ignorado: já em progresso")
                return
            }
            isRendering = true

            val result  = mutable[currentIndex]
            val hasNext = currentIndex < mutable.lastIndex

            // CORREÇÃO: clearView reseta TODOS os elementos antes de cada render,
            // incluindo tvOriginal que estava sendo esquecido no reset.
            clearView(view)

            try {
                if (gameModeEnabled) {
                    setupGameMode(
                        view          = view,
                        result        = result,
                        hasNext       = hasNext,
                        themedCtx     = themedCtx,
                        context       = context,
                        windowManager = windowManager,
                        onDismiss     = { removeOverlay(windowManager) },
                        onNext        = { currentIndex++; renderCurrent() },
                        onEditOriginal = { newOriginal ->
                            mutable[currentIndex] = mutable[currentIndex].copy(originalText = newOriginal)
                            retranslateAndRender(context, mutable, currentIndex) { renderCurrent() }
                        }
                    )
                } else {
                    setupTranslationMode(
                        view              = view,
                        result            = result,
                        hasNext           = hasNext,
                        context           = context,
                        windowManager     = windowManager,
                        onDismiss         = { removeOverlay(windowManager) },
                        onNext            = { currentIndex++; renderCurrent() },
                        onEditOriginal    = { newOriginal ->
                            mutable[currentIndex] = mutable[currentIndex].copy(originalText = newOriginal)
                            retranslateAndRender(context, mutable, currentIndex) { renderCurrent() }
                        },
                        onEditTranslation = { newPt ->
                            mutable[currentIndex] = mutable[currentIndex].copy(translatedText = newPt)
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

            isRendering = false
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
    // RESET ENTRE RENDERS
    // ─────────────────────────────────────────────

    private fun clearView(view: View) {
        // CORREÇÃO: tvOriginal também precisa ser resetado para GONE entre renders.
        // Sem isso, ao navegar entre resultados no modo tradução, o tvOriginal do
        // resultado anterior permanecia visível durante o render do próximo.
        view.findViewById<TextView>(R.id.tvOriginal).visibility               = View.GONE

        view.findViewById<LinearLayout>(R.id.containerTranslation).visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.containerGame).visibility        = View.GONE
        view.findViewById<TextView>(R.id.btnEditOriginal).visibility          = View.GONE
        view.findViewById<TextView>(R.id.btnEditTranslation).visibility       = View.GONE
        view.findViewById<TextView>(R.id.btnEditGameSentence).visibility      = View.GONE
        view.findViewById<TextView>(R.id.btnNext).apply {
            visibility = View.GONE; setOnClickListener(null)
        }
        view.findViewById<TextView>(R.id.btnSkip).apply {
            visibility = View.GONE; setOnClickListener(null)
        }
        view.findViewById<LinearLayout>(R.id.containerCopyButtons).visibility = View.GONE
        view.findViewById<TextView>(R.id.btnCopyOriginal).visibility          = View.GONE
        view.findViewById<TextView>(R.id.btnCopyTranslation).visibility       = View.GONE
        view.findViewById<ChipGroup>(R.id.chipGroupAnswer).removeAllViews()
        view.findViewById<ChipGroup>(R.id.chipGroupWords).removeAllViews()
        view.findViewById<TextView>(R.id.tvResult).visibility                 = View.GONE
    }

    // ─────────────────────────────────────────────
    // RE-TRADUÇÃO
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
        onDismiss: () -> Unit,
        onNext: () -> Unit,
        onEditOriginal: (String) -> Unit,
        onEditTranslation: (String) -> Unit
    ) {
        view.findViewById<LinearLayout>(R.id.containerTranslation).visibility = View.VISIBLE

        val tvOriginal    = view.findViewById<TextView>(R.id.tvOriginal)
        val tvTranslation = view.findViewById<TextView>(R.id.tvTranslation)

        // CORREÇÃO: tvOriginal.visibility definida EXPLICITAMENTE aqui.
        // Antes dependia do estado residual do XML/clearView, o que causava
        // inconsistência entre o primeiro render e os renders subsequentes.
        tvOriginal.apply {
            text       = "🇬🇧 ${result.originalText}"
            visibility = View.VISIBLE
        }
        tvTranslation.text = "🇧🇷 ${result.translatedText}"

        setupCopyButtons(view, context, result.originalText, result.translatedText)

        // CORREÇÃO DO BUG PRINCIPAL: btnEditOriginal estava tendo sua visibilidade
        // definida como VISIBLE aqui, mas o problema era uma race condition sutil:
        // quando `retranslateAndRender` chamava `renderCurrent()` de dentro de uma
        // callback assíncrona do OcrProcessor, a flag `isRendering` ainda era `true`
        // do render anterior, fazendo o segundo render ser abortado silenciosamente
        // pelo guard `if (isRendering) return`.
        //
        // Resultado: clearView() havia rodado (botão = GONE), mas setupTranslationMode()
        // nunca rodou no segundo render (botão permanecia GONE).
        //
        // A correção já está no fluxo: isRendering = false ao final de renderCurrent(),
        // garantindo que retranslateAndRender → onDone() → renderCurrent() encontre
        // isRendering = false e execute completamente.
        //
        // Aqui apenas garantimos visibilidade explícita para ambos os botões EN e PT.
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

        setupCompletionButton(view, hasNext, onDismiss, onNext)
    }

    // ─────────────────────────────────────────────
    // MODO GAME
    // ─────────────────────────────────────────────

    private fun setupGameMode(
        view: View,
        result: TextResult,
        hasNext: Boolean,
        themedCtx: Context,
        context: Context,
        windowManager: WindowManager,
        onDismiss: () -> Unit,
        onNext: () -> Unit,
        onEditOriginal: (String) -> Unit
    ) {
        view.findViewById<LinearLayout>(R.id.containerGame).visibility = View.VISIBLE

        val tvInstruction   = view.findViewById<TextView>(R.id.tvInstruction)
        val chipGroupAnswer = view.findViewById<ChipGroup>(R.id.chipGroupAnswer)
        val chipGroupWords  = view.findViewById<ChipGroup>(R.id.chipGroupWords)
        val tvResult        = view.findViewById<TextView>(R.id.tvResult)
        val btnNext         = view.findViewById<TextView>(R.id.btnNext)
        val btnSkip         = view.findViewById<TextView>(R.id.btnSkip)

        val originalHint = result.originalText
        val sentenceToSort = result.translatedText
            .takeIf { it.isNotBlank() && it.trim() != originalHint.trim() }
            ?: run {
                Log.w(TAG, "Tradução indisponível para '$originalHint' — EN")
                Toast.makeText(context, "⚠️ Tradução indisponível — jogo em inglês.", Toast.LENGTH_LONG).show()
                originalHint
            }

        setupCopyButtons(view, context, originalHint, sentenceToSort)

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
                        Toast.makeText(context, "🔄 Recriando jogo...", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        view.findViewById<TextView>(R.id.btnEditGameSentence).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                OverlayEditor.show(
                    context       = context,
                    windowManager = windowManager,
                    title         = "✏️ Corrigir frase do jogo",
                    initialText   = sentenceToSort,
                    hint          = "Corrija a frase...",
                    gravityY      = (220 * context.resources.displayMetrics.density).toInt(),
                    onConfirm     = { newPt ->
                        buildGameChips(
                            view, newPt, originalHint,
                            chipGroupAnswer, chipGroupWords,
                            tvResult, btnNext, btnSkip, tvInstruction,
                            hasNext, themedCtx, context, onDismiss, onNext
                        )
                        Toast.makeText(context, "🎮 Jogo atualizado!", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        buildGameChips(
            view, sentenceToSort, originalHint,
            chipGroupAnswer, chipGroupWords,
            tvResult, btnNext, btnSkip, tvInstruction,
            hasNext, themedCtx, context, onDismiss, onNext
        )
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
        themedCtx: Context,
        context: Context,
        onDismiss: () -> Unit,
        onNext: () -> Unit
    ) {
        chipGroupAnswer.removeAllViews()
        chipGroupWords.removeAllViews()
        tvResult.visibility = View.GONE
        btnNext.visibility  = View.GONE
        btnSkip.visibility  = View.VISIBLE

        val lang = if (sentence.trim() == originalHint.trim()) "🇬🇧" else "🇧🇷"
        tvInstruction.text = "🇬🇧 $originalHint\n\nMonte a frase $lang:"

        val words = sentence.split(" ").filter { it.isNotBlank() }

        if (words.isEmpty()) {
            tvResult.text       = "💡 $sentence"
            tvResult.setTextColor(Color.WHITE)
            tvResult.visibility = View.VISIBLE
            btnSkip.visibility  = View.GONE
            setupCompletionButton(view, hasNext, onDismiss, onNext)
            return
        }

        val available = words.shuffled().toMutableList()
        val selected  = mutableListOf<String>()
        var done      = false

        var refreshW: () -> Unit = {}
        var refreshA: () -> Unit = {}

        refreshW = {
            chipGroupWords.removeAllViews()
            if (!done) {
                available.forEach { word ->
                    chipGroupWords.addView(buildWordChip(themedCtx, word) {
                        selected.add(word)
                        available.remove(word)
                        refreshW()
                        refreshA()
                        checkAnswer(
                            selected, words, tvResult, sentence,
                            view, hasNext, context, onDismiss, onNext
                        ) {
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
                chipGroupAnswer.addView(buildAnswerChip(themedCtx, word, !done) {
                    if (!done) {
                        selected.remove(word)
                        available.add(word)
                        tvResult.visibility = View.GONE
                        btnNext.visibility  = View.GONE
                        btnSkip.visibility  = View.VISIBLE
                        refreshW()
                        refreshA()
                    }
                })
            }
        }

        refreshW()

        btnSkip.setOnClickListener {
            done = true
            tvResult.text       = "💡 Resposta: $sentence"
            tvResult.setTextColor(Color.YELLOW)
            tvResult.visibility = View.VISIBLE
            btnSkip.visibility  = View.GONE
            setupCopyButtons(view, context, originalHint, sentence)
            setupCompletionButton(view, hasNext, onDismiss, onNext)
            chipGroupWords.removeAllViews()
        }
    }

    // ─────────────────────────────────────────────
    // BOTÃO DE CONCLUSÃO
    // ─────────────────────────────────────────────

    private fun setupCompletionButton(
        view: View,
        hasNext: Boolean,
        onDismiss: () -> Unit,
        onNext: () -> Unit
    ) {
        view.findViewById<TextView>(R.id.btnNext).apply {
            visibility = View.VISIBLE
            text       = if (hasNext) "Próxima frase ▶" else "✓ Concluído"
            setOnClickListener(null)
            setOnClickListener { if (hasNext) onNext() else onDismiss() }
        }
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

    // ─────────────────────────────────────────────
    // CHIPS
    // ─────────────────────────────────────────────

    private fun buildAnswerChip(ctx: Context, word: String, enabled: Boolean, onClick: () -> Unit) =
        Chip(ctx).apply {
            text        = word
            isClickable = enabled
            setChipBackgroundColorResource(android.R.color.holo_blue_light)
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
        }

    private fun buildWordChip(ctx: Context, word: String, onClick: () -> Unit) =
        Chip(ctx).apply {
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
        context: Context,
        onDismiss: () -> Unit,
        onNext: () -> Unit,
        onCorrect: () -> Unit
    ) {
        when {
            selected == correct -> {
                onCorrect()
                tvResult.text       = "✅ Correto! $translation"
                tvResult.setTextColor(Color.GREEN)
                tvResult.visibility = View.VISIBLE
                view.findViewById<TextView>(R.id.btnSkip).visibility = View.GONE
                setupCompletionButton(view, hasNext, onDismiss, onNext)
            }
            selected.size == correct.size -> {
                tvResult.text       = "❌ Quase! Toque nas palavras para reorganizar."
                tvResult.setTextColor(Color.RED)
                tvResult.visibility = View.VISIBLE
            }
            else -> tvResult.visibility = View.GONE
        }
    }

    // ─────────────────────────────────────────────
    // UTILITÁRIOS
    // ─────────────────────────────────────────────

    private fun removeOverlay(windowManager: WindowManager) {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        isRendering = false
    }
}