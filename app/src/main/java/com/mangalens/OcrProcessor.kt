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
    val detectedLanguage: String = "und"
)

object OcrProcessor {

    private const val TAG = "MangaLens_OCR"

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val translators  = mutableMapOf<String, Translator>()
    private val modelReady   = mutableMapOf<String, Boolean>()

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
    // PROCESSO PRINCIPAL
    // ─────────────────────────────────────────────

    fun process(
        context: Context,
        bitmap: Bitmap,
        cropRect: Rect? = null,
        onResult: (List<TextResult>) -> Unit
    ) {
        // Aplica recorte se fornecido
        val source: Bitmap = applyCrop(bitmap, cropRect)

        Log.d(TAG, "OCR iniciando em ${source.width}x${source.height} | crop=$cropRect")

        recognizer.process(InputImage.fromBitmap(source, 0))
            .addOnSuccessListener { visionText ->
                val raw = visionText.text.trim()
                Log.d(TAG, "OCR completo — ${raw.length} chars: ${raw.take(200)}")

                val blocks: List<Pair<String, Rect?>> = visionText.textBlocks
                    .map { Pair(it.text.trim(), it.boundingBox) }
                    .filter { it.first.isNotBlank() }

                Log.d(TAG, "Blocos brutos: ${blocks.size}")

                if (blocks.isEmpty()) {
                    Log.w(TAG, "Nenhum texto detectado")
                    onResult(emptyList())
                    return@addOnSuccessListener
                }

                val merged: List<Pair<String, Rect?>> =
                    mergeNearbyBlocks(blocks, source.height / 10)

                Log.d(TAG, "Após merge: ${merged.size} blocos")

                translateAll(merged, onResult)
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
    // MERGE DE BLOCOS PRÓXIMOS
    // ─────────────────────────────────────────────

    private fun mergeNearbyBlocks(
        blocks: List<Pair<String, Rect?>>,
        gapThreshold: Int
    ): List<Pair<String, Rect?>> {
        if (blocks.isEmpty()) return emptyList()

        val sorted = blocks.sortedBy { it.second?.top ?: Int.MAX_VALUE }
        val out    = mutableListOf<Pair<String, Rect?>>()
        var text   = sorted[0].first
        var box: Rect? = sorted[0].second?.let { Rect(it) }

        for (i in 1 until sorted.size) {
            val (nt: String, nb: Rect?) = sorted[i]
            val gap = (nb?.top ?: Int.MAX_VALUE) - (box?.bottom ?: Int.MIN_VALUE)
            if (gap <= gapThreshold) {
                text += " $nt"
                val cb = box
                if (cb != null && nb != null) {
                    box = Rect(minOf(cb.left, nb.left), minOf(cb.top, nb.top),
                        maxOf(cb.right, nb.right), maxOf(cb.bottom, nb.bottom))
                }
            } else {
                out.add(Pair(text.trim(), box))
                text = nt; box = nb?.let { Rect(it) }
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
        onResult: (List<TextResult>) -> Unit
    ) {
        if (blocks.isEmpty()) { onResult(emptyList()); return }

        val out     = arrayOfNulls<TextResult>(blocks.size)
        var pending = blocks.size

        blocks.forEachIndexed { i: Int, (text: String, box: Rect?) ->
            val isJa: Boolean    = containsJapanese(text)
            val lang: String     = if (isJa) "ja" else "en"
            val src: String      = if (isJa) TranslateLanguage.JAPANESE else TranslateLanguage.ENGLISH
            val tgt: String      = TranslateLanguage.PORTUGUESE
            val key: String      = "$src->$tgt"

            Log.d(TAG, "  Bloco $i [$lang]: \"${text.take(60)}\"")

            // Se o modelo não estiver baixado, retorna o original
            if (modelReady[key] != true) {
                Log.w(TAG, "  Modelo $key não disponível — retornando original")
                synchronized(out) {
                    out[i] = TextResult(text, text, box, lang)
                    if (--pending == 0) onResult(out.filterNotNull())
                }
                return@forEachIndexed
            }

            getOrCreateTranslator(src, tgt).translate(text)
                .addOnSuccessListener { translated: String ->
                    Log.d(TAG, "  Traduzido $i: \"${translated.take(60)}\"")
                    synchronized(out) {
                        out[i] = TextResult(text, translated, box, lang)
                        if (--pending == 0) onResult(out.filterNotNull())
                    }
                }
                .addOnFailureListener { e: Exception ->
                    Log.e(TAG, "  Falha tradução $i: ${e.message}")
                    synchronized(out) {
                        out[i] = TextResult(text, text, box, lang)
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