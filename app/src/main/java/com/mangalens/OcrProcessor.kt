package com.mangalens

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class TextResult(
    val originalText: String,
    val translatedText: String,
    val boundingBox: android.graphics.Rect?  // posição na tela
)

object OcrProcessor {

    // Reconhecedor de texto (OCR)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Tradutor EN → PT
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.PORTUGUESE)
            .build()
    )

    private var modelDownloaded = false

    /**
     * Baixa o modelo de tradução offline na primeira vez.
     * Depois fica em cache no dispositivo.
     */
    fun downloadModelIfNeeded(onReady: () -> Unit) {
        if (modelDownloaded) { onReady(); return }

        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                modelDownloaded = true
                onReady()
            }
    }

    /**
     * 1. Roda OCR no bitmap
     * 2. Para cada bloco de texto, traduz
     * 3. Retorna lista com texto original, tradução e posição
     */
    fun process(
        context: Context,
        bitmap: Bitmap,
        onResult: (List<TextResult>) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val blocks = visionText.textBlocks
                if (blocks.isEmpty()) return@addOnSuccessListener

                val results = mutableListOf<TextResult>()
                var pending = blocks.size

                blocks.forEach { block ->
                    val text = block.text
                    val box = block.boundingBox

                    translator.translate(text)
                        .addOnSuccessListener { translated ->
                            results.add(TextResult(text, translated, box))
                            pending--
                            if (pending == 0) onResult(results) // todos traduzidos
                        }
                        .addOnFailureListener {
                            results.add(TextResult(text, "[erro]", box))
                            pending--
                            if (pending == 0) onResult(results)
                        }
                }
            }
    }
}