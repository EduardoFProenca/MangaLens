package com.mangalens

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.*
import android.widget.*
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

object GameOverlayManager {

    private var overlayView: View? = null
    private var gameModeEnabled = true // ligado por padrão

    /**
     * Recebe os resultados do OCR e decide o que mostrar:
     * - Modo Game OFF → mostra tradução diretamente
     * - Modo Game ON  → esconde o texto original e exibe o mini-game
     */
    fun show(
        context: Context,
        windowManager: WindowManager,
        results: List<TextResult>
    ) {
        removeOverlay(windowManager)
        if (results.isEmpty()) return

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.layout_game_overlay, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        if (gameModeEnabled) {
            setupGameMode(view, results.first(), windowManager)
        } else {
            setupTranslationMode(view, results)
        }

        // Botão fechar
        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            removeOverlay(windowManager)
        }

        windowManager.addView(view, params)
        overlayView = view
    }

    /**
     * MODO NORMAL: apenas exibe a tradução.
     */
    private fun setupTranslationMode(view: View, results: List<TextResult>) {
        val container = view.findViewById<LinearLayout>(R.id.containerTranslation)
        val gameContainer = view.findViewById<LinearLayout>(R.id.containerGame)

        container.visibility = View.VISIBLE
        gameContainer.visibility = View.GONE

        val tvTranslation = view.findViewById<TextView>(R.id.tvTranslation)
        tvTranslation.text = results.joinToString("\n\n") { it.translatedText }
    }

    /**
     * MODO GAME:
     * 1. Pega a frase original
     * 2. Embaralha as palavras
     * 3. Exibe como Chips para o usuário montar
     * 4. Ao acertar, revela a tradução
     */
    private fun setupGameMode(
        view: View,
        result: TextResult,
        windowManager: WindowManager
    ) {
        val container = view.findViewById<LinearLayout>(R.id.containerTranslation)
        val gameContainer = view.findViewById<LinearLayout>(R.id.containerGame)

        container.visibility = View.GONE
        gameContainer.visibility = View.VISIBLE

        val tvInstruction = view.findViewById<TextView>(R.id.tvInstruction)
        val chipGroupAnswer = view.findViewById<ChipGroup>(R.id.chipGroupAnswer)
        val chipGroupWords = view.findViewById<ChipGroup>(R.id.chipGroupWords)
        val tvResult = view.findViewById<TextView>(R.id.tvResult)

        // Palavras embaralhadas
        val words = result.originalText.split(" ").filter { it.isNotBlank() }
        val shuffled = words.shuffled()
        val selectedWords = mutableListOf<String>()

        tvInstruction.text = "Monte a frase em inglês:"

        // Cria um Chip para cada palavra embaralhada
        shuffled.forEach { word ->
            val chip = Chip(view.context).apply {
                text = word
                isClickable = true
                setOnClickListener {
                    // Move a palavra para a área de resposta
                    selectedWords.add(word)
                    chipGroupAnswer.addView(createAnswerChip(view.context, word, selectedWords, chipGroupAnswer, this, chipGroupWords))
                    chipGroupWords.removeView(this)

                    // Verifica se a frase está correta
                    checkAnswer(selectedWords, words, tvResult, result.translatedText)
                }
            }
            chipGroupWords.addView(chip)
        }
    }

    private fun createAnswerChip(
        context: Context,
        word: String,
        selectedWords: MutableList<String>,
        answerGroup: ChipGroup,
        sourceChip: Chip,
        wordsGroup: ChipGroup
    ): Chip {
        return Chip(context).apply {
            text = word
            isClickable = true
            setChipBackgroundColorResource(android.R.color.holo_blue_light)
            setOnClickListener {
                // Devolve a palavra para as opções
                selectedWords.remove(word)
                answerGroup.removeView(this)
                wordsGroup.addView(sourceChip)
            }
        }
    }

    private fun checkAnswer(
        selected: List<String>,
        correct: List<String>,
        tvResult: TextView,
        translation: String
    ) {
        if (selected == correct) {
            tvResult.visibility = View.VISIBLE
            tvResult.text = "✅ Correto!\n🇧🇷 $translation"
            tvResult.setTextColor(Color.GREEN)
        } else if (selected.size == correct.size) {
            tvResult.visibility = View.VISIBLE
            tvResult.text = "❌ Tente novamente!"
            tvResult.setTextColor(Color.RED)
        } else {
            tvResult.visibility = View.GONE
        }
    }

    private fun removeOverlay(windowManager: WindowManager) {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }
}