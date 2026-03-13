package com.mangalens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class TextResult(
    val originalText: String,
    val translatedText: String,
    val boundingBox: Rect?,
    val detectedLanguage: String = "und"   // BCP-47: "en", "ja", "und" = indefinido
)

object OcrProcessor {

    private const val TAG = "MangaLens_OCR"

    // ── Reconhecedores ────────────────────────────
    private val latinRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val japaneseRecognizer: TextRecognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    // ── Identificador de idioma ───────────────────
    private val langId = LanguageIdentification.getClient()

    // ── Tradutores (lazy, criados sob demanda) ────
    private val translators = mutableMapOf<String, com.google.mlkit.nl.translate.Translator>()

    // ── Estado de download ────────────────────────
    // Mapeia "en->pt", "ja->pt" etc. → true se pronto
    private val modelReady = mutableMapOf<String, Boolean>()

    // ─────────────────────────────────────────────
    // DOWNLOAD DOS MODELOS
    // ─────────────────────────────────────────────

    /**
     * Pré-baixa os modelos EN→PT e JA→PT.
     * Chamado na inicialização do app.
     */
    fun downloadModelsIfNeeded(onReady: () -> Unit) {
        val pairs = listOf(
            TranslateLanguage.ENGLISH  to TranslateLanguage.PORTUGUESE,
            TranslateLanguage.JAPANESE to TranslateLanguage.PORTUGUESE
        )
        var remaining = pairs.size

        pairs.forEach { (src, tgt) ->
            val key = "$src->$tgt"
            val translator = getOrCreateTranslator(src, tgt)

            translator.downloadModelIfNeeded()
                .addOnSuccessListener {
                    modelReady[key] = true
                    Log.d(TAG, "Modelo pronto: $key")
                    remaining--
                    if (remaining == 0) onReady()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Falha modelo $key: ${e.message}")
                    remaining--
                    if (remaining == 0) onReady()
                }
        }
    }

    // Atalho de compatibilidade para chamadas antigas com um único callback
    fun downloadModelIfNeeded(onReady: () -> Unit) = downloadModelsIfNeeded(onReady)

    // ─────────────────────────────────────────────
    // PROCESSAMENTO PRINCIPAL
    // ─────────────────────────────────────────────

    /**
     * @param cropRect  Região da tela a processar (null = tela inteira)
     * @param onResult  Callback com lista de resultados traduzidos
     */
    fun process(
        context: Context,
        bitmap: Bitmap,
        cropRect: Rect? = null,
        onResult: (List<TextResult>) -> Unit
    ) {
        // Aplica crop se fornecido
        val source = if (cropRect != null) {
            val safeRect = cropRect.intersect(Rect(0, 0, bitmap.width, bitmap.height))
                .let { cropRect }
            Bitmap.createBitmap(
                bitmap,
                cropRect.left.coerceAtLeast(0),
                cropRect.top.coerceAtLeast(0),
                cropRect.width().coerceAtMost(bitmap.width - cropRect.left.coerceAtLeast(0)),
                cropRect.height().coerceAtMost(bitmap.height - cropRect.top.coerceAtLeast(0))
            )
        } else bitmap

        Log.d(TAG, "OCR em ${source.width}x${source.height} (crop=${cropRect != null})")

        // Tenta japonês primeiro (detecta kanji/kana), fallback para latim
        runOcr(japaneseRecognizer, source) { jpBlocks ->
            val jpText = jpBlocks.joinToString(" ") { it.first }
            val hasJapanese = containsJapanese(jpText)

            if (hasJapanese) {
                Log.d(TAG, "Japonês detectado — usando recognizer JP")
                val merged = mergeNearbyBlocks(jpBlocks, source.height / 12)
                translateAllWithLangDetect(merged, onResult)
            } else {
                // Tenta latim para EN e outros idiomas latinos
                runOcr(latinRecognizer, source) { latBlocks ->
                    Log.d(TAG, "Usando recognizer Latino")
                    val merged = mergeNearbyBlocks(latBlocks, source.height / 12)
                    translateAllWithLangDetect(merged, onResult)
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // OCR GENÉRICO
    // ─────────────────────────────────────────────

    private fun runOcr(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        onBlocks: (List<Pair<String, Rect?>>) -> Unit
    ) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText ->
                val blocks = visionText.textBlocks.map { Pair(it.text.trim(), it.boundingBox) }
                Log.d(TAG, "OCR blocos brutos: ${blocks.size} | preview: ${visionText.text.take(100)}")
                onBlocks(blocks)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR falhou: ${e.message}")
                onBlocks(emptyList())
            }
    }

    // ─────────────────────────────────────────────
    // MERGE DE BLOCOS PRÓXIMOS
    // ─────────────────────────────────────────────

    private fun mergeNearbyBlocks(
        blocks: List<Pair<String, Rect?>>,
        verticalGapThreshold: Int
    ): List<Pair<String, Rect?>> {
        if (blocks.isEmpty()) return emptyList()

        val sorted = blocks.sortedBy { it.second?.top ?: Int.MAX_VALUE }
        val merged = mutableListOf<Pair<String, Rect?>>()
        var currentText = sorted[0].first
        var currentBox  = sorted[0].second?.let { Rect(it) }

        for (i in 1 until sorted.size) {
            val (nextText, nextBox) = sorted[i]
            val gap = (nextBox?.top ?: Int.MAX_VALUE) - (currentBox?.bottom ?: Int.MIN_VALUE)

            if (gap <= verticalGapThreshold) {
                currentText += " $nextText"
                if (currentBox != null && nextBox != null) {
                    currentBox = Rect(
                        minOf(currentBox!!.left,   nextBox.left),
                        minOf(currentBox!!.top,    nextBox.top),
                        maxOf(currentBox!!.right,  nextBox.right),
                        maxOf(currentBox!!.bottom, nextBox.bottom)
                    )
                }
            } else {
                merged.add(Pair(currentText.trim(), currentBox))
                currentText = nextText
                currentBox  = nextBox?.let { Rect(it) }
            }
        }
        merged.add(Pair(currentText.trim(), currentBox))
        return merged.filter { it.first.isNotBlank() }
    }

    // ─────────────────────────────────────────────
    // DETECÇÃO DE IDIOMA + TRADUÇÃO
    // ─────────────────────────────────────────────

    private fun translateAllWithLangDetect(
        blocks: List<Pair<String, Rect?>>,
        onResult: (List<TextResult>) -> Unit
    ) {
        if (blocks.isEmpty()) { onResult(emptyList()); return }

        val results  = arrayOfNulls<TextResult>(blocks.size)
        var pending  = blocks.size

        blocks.forEachIndexed { i, (text, box) ->
            langId.identifyLanguage(text)
                .addOnSuccessListener { langCode ->
                    val detectedLang = langCode.takeIf { it != "und" }
                        ?: if (containsJapanese(text)) "ja" else "en"

                    Log.d(TAG, "Bloco $i lang=$detectedLang: \"${text.take(60)}\"")

                    // Mapeia código BCP-47 → TranslateLanguage
                    val srcLang = when (detectedLang) {
                        "ja"       -> TranslateLanguage.JAPANESE
                        "zh", "zh-Hans", "zh-Hant" -> TranslateLanguage.CHINESE
                        "ko"       -> TranslateLanguage.KOREAN
                        else       -> TranslateLanguage.ENGLISH
                    }

                    val key = "$srcLang->${TranslateLanguage.PORTUGUESE}"
                    if (modelReady[key] != true) {
                        // Modelo não baixado: retorna original
                        Log.w(TAG, "Modelo $key não disponível")
                        synchronized(results) {
                            results[i] = TextResult(text, text, box, detectedLang)
                            pending--
                            if (pending == 0) onResult(results.filterNotNull())
                        }
                        return@addOnSuccessListener
                    }

                    getOrCreateTranslator(srcLang, TranslateLanguage.PORTUGUESE)
                        .translate(text)
                        .addOnSuccessListener { translated ->
                            synchronized(results) {
                                results[i] = TextResult(text, translated, box, detectedLang)
                                pending--
                                if (pending == 0) onResult(results.filterNotNull())
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Tradução $i falhou: ${e.message}")
                            synchronized(results) {
                                results[i] = TextResult(text, text, box, detectedLang)
                                pending--
                                if (pending == 0) onResult(results.filterNotNull())
                            }
                        }
                }
                .addOnFailureListener {
                    // Se langId falhar, assume inglês
                    synchronized(results) {
                        results[i] = TextResult(text, text, box, "en")
                        pending--
                        if (pending == 0) onResult(results.filterNotNull())
                    }
                }
        }
    }

    // ─────────────────────────────────────────────
    // UTILITÁRIOS
    // ─────────────────────────────────────────────

    /** Verifica se o texto contém caracteres japoneses (hiragana, katakana, kanji) */
    private fun containsJapanese(text: String): Boolean =
        text.any { c ->
            Character.UnicodeBlock.of(c) in setOf(
                Character.UnicodeBlock.HIRAGANA,
                Character.UnicodeBlock.KATAKANA,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
            )
        }

    private fun getOrCreateTranslator(
        src: String,
        tgt: String
    ): com.google.mlkit.nl.translate.Translator {
        val key = "$src->$tgt"
        return translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build()
            )
        }
    }
}