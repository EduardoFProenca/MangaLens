package com.mangalens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class TextResult(
    val originalText: String,
    val translatedText: String,
    val boundingBox: Rect?,
    val detectedLanguage: String = "und",
    val rawText: String = originalText
)

object OcrProcessor {

    private const val TAG = "MangaLens_OCR"

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val translators = mutableMapOf<String, Translator>()
    private val modelReady  = mutableMapOf<String, Boolean>()

    // ─────────────────────────────────────────────
    // DOWNLOAD
    // ─────────────────────────────────────────────

    fun downloadModelsIfNeeded(onReady: () -> Unit) {
        val pairs = listOf(
            TranslateLanguage.ENGLISH  to TranslateLanguage.PORTUGUESE,
            TranslateLanguage.JAPANESE to TranslateLanguage.PORTUGUESE
        )
        var remaining = pairs.size
        pairs.forEach { (src: String, tgt: String) ->
            val key = "$src->$tgt"
            getOrCreateTranslator(src, tgt).downloadModelIfNeeded()
                .addOnSuccessListener {
                    modelReady[key] = true
                    Log.d(TAG, "Modelo pronto: $key")
                    if (--remaining == 0) onReady()
                }
                .addOnFailureListener { e: Exception ->
                    Log.e(TAG, "Falha $key: ${e.message}")
                    if (--remaining == 0) onReady()
                }
        }
    }

    fun downloadModelIfNeeded(onReady: () -> Unit) = downloadModelsIfNeeded(onReady)

    // ─────────────────────────────────────────────
    // RE-TRADUÇÃO APÓS EDIÇÃO DO ORIGINAL
    // ─────────────────────────────────────────────

    /**
     * Traduz um texto avulso (após o usuário corrigir o original EN).
     * Detecta automaticamente se é japonês ou inglês.
     * Chama [onResult] com o texto traduzido (ou o próprio texto se falhar).
     */
    fun retranslate(text: String, onResult: (String) -> Unit) {
        val isJa  = containsJapanese(text)
        val src   = if (isJa) TranslateLanguage.JAPANESE else TranslateLanguage.ENGLISH
        val tgt   = TranslateLanguage.PORTUGUESE
        val key   = "$src->$tgt"

        if (modelReady[key] != true) {
            Log.w(TAG, "retranslate: modelo $key não disponível")
            onResult(text)   // fallback: retorna o próprio texto corrigido sem traduzir
            return
        }

        getOrCreateTranslator(src, tgt).translate(text)
            .addOnSuccessListener { translated ->
                Log.d(TAG, "retranslate: \"$text\" → \"$translated\"")
                onResult(translated)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "retranslate falhou: ${e.message}")
                onResult(text)
            }
    }

    // ─────────────────────────────────────────────
    // PROCESSO PRINCIPAL
    // ─────────────────────────────────────────────

    fun process(
        context: Context,
        bitmap: Bitmap,
        cropRect: Rect? = null,
        onResult: (List<TextResult>) -> Unit
    ) {
        val source: Bitmap = applyCrop(bitmap, cropRect)
        Log.d(TAG, "OCR em ${source.width}x${source.height}")

        recognizer.process(InputImage.fromBitmap(source, 0))
            .addOnSuccessListener { visionText ->
                val raw = visionText.text.trim()
                Log.d(TAG, "OCR raw: ${raw.take(200)}")

                val blocks: List<Pair<String, Rect?>> = visionText.textBlocks
                    .map { Pair(it.text.trim(), it.boundingBox) }
                    .filter { it.first.isNotBlank() }

                if (blocks.isEmpty()) { onResult(emptyList()); return@addOnSuccessListener }

                val merged    = mergeNearbyBlocks(blocks, source.height / 10)
                val corrected = merged.map { (rawText, box) ->
                    val fixed = CharacterCorrector.correct(rawText)
                    if (fixed != rawText) Log.d(TAG, "Corrigido: \"$rawText\" → \"$fixed\"")
                    Pair(fixed, box)
                }

                val rawMap = merged.zip(corrected).associate { (orig, fix) -> fix.first to orig.first }
                translateAll(corrected, rawMap, onResult)
            }
            .addOnFailureListener { e: Exception ->
                Log.e(TAG, "OCR falhou: ${e.message}")
                onResult(emptyList())
            }
    }

    // ─────────────────────────────────────────────
    // CROP
    // ─────────────────────────────────────────────

    private fun applyCrop(bitmap: Bitmap, crop: Rect?): Bitmap {
        if (crop == null) return bitmap
        val l = crop.left.coerceIn(0, bitmap.width  - 1)
        val t = crop.top .coerceIn(0, bitmap.height - 1)
        val w = crop.width() .coerceAtMost(bitmap.width  - l).coerceAtLeast(1)
        val h = crop.height().coerceAtMost(bitmap.height - t).coerceAtLeast(1)
        return Bitmap.createBitmap(bitmap, l, t, w, h)
    }

    // ─────────────────────────────────────────────
    // MERGE DE BLOCOS
    // ─────────────────────────────────────────────

    private fun mergeNearbyBlocks(
        blocks: List<Pair<String, Rect?>>, gapThreshold: Int
    ): List<Pair<String, Rect?>> {
        if (blocks.isEmpty()) return emptyList()
        val sorted = blocks.sortedBy { it.second?.top ?: Int.MAX_VALUE }
        val out    = mutableListOf<Pair<String, Rect?>>()
        var text   = sorted[0].first
        var box: Rect? = sorted[0].second?.let { Rect(it) }

        for (i in 1 until sorted.size) {
            val (nt, nb) = sorted[i]
            val gap = (nb?.top ?: Int.MAX_VALUE) - (box?.bottom ?: Int.MIN_VALUE)
            if (gap <= gapThreshold) {
                text += " $nt"
                val cb = box
                if (cb != null && nb != null)
                    box = Rect(minOf(cb.left, nb.left), minOf(cb.top, nb.top),
                        maxOf(cb.right, nb.right), maxOf(cb.bottom, nb.bottom))
            } else {
                out.add(Pair(text.trim(), box)); text = nt; box = nb?.let { Rect(it) }
            }
        }
        out.add(Pair(text.trim(), box))
        return out.filter { it.first.isNotBlank() }
    }

    // ─────────────────────────────────────────────
    // TRADUÇÃO
    // ─────────────────────────────────────────────

    private fun translateAll(
        blocks: List<Pair<String, Rect?>>,
        rawMap: Map<String, String>,
        onResult: (List<TextResult>) -> Unit
    ) {
        if (blocks.isEmpty()) { onResult(emptyList()); return }

        val out     = arrayOfNulls<TextResult>(blocks.size)
        var pending = blocks.size

        blocks.forEachIndexed { i, (text, box) ->
            val isJa  = containsJapanese(text)
            val lang  = if (isJa) "ja" else "en"
            val src   = if (isJa) TranslateLanguage.JAPANESE else TranslateLanguage.ENGLISH
            val tgt   = TranslateLanguage.PORTUGUESE
            val key   = "$src->$tgt"
            val raw   = rawMap[text] ?: text

            if (modelReady[key] != true) {
                synchronized(out) {
                    out[i] = TextResult(text, text, box, lang, raw)
                    if (--pending == 0) onResult(out.filterNotNull())
                }
                return@forEachIndexed
            }

            getOrCreateTranslator(src, tgt).translate(text)
                .addOnSuccessListener { translated ->
                    synchronized(out) {
                        out[i] = TextResult(text, translated, box, lang, raw)
                        if (--pending == 0) onResult(out.filterNotNull())
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Falha tradução $i: ${e.message}")
                    synchronized(out) {
                        out[i] = TextResult(text, text, box, lang, raw)
                        if (--pending == 0) onResult(out.filterNotNull())
                    }
                }
        }
    }

    // ─────────────────────────────────────────────
    // UTILITÁRIOS
    // ─────────────────────────────────────────────

    private fun containsJapanese(text: String): Boolean {
        for (c in text) {
            val b = Character.UnicodeBlock.of(c)
            if (b == Character.UnicodeBlock.HIRAGANA ||
                b == Character.UnicodeBlock.KATAKANA  ||
                b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B)
                return true
        }
        return false
    }

    private fun getOrCreateTranslator(src: String, tgt: String): Translator =
        translators.getOrPut("$src->$tgt") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build()
            )
        }
}