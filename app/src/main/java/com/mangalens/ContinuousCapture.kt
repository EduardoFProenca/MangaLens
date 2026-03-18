package com.mangalens

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.*

/**
 * Captura contínua com lógica anti-retroalimentação:
 *
 * PROBLEMA RAIZ: o VirtualDisplay captura tudo que está na tela, incluindo
 * o próprio overlay de tradução. O OCR então lê o texto em português que o
 * app escreveu, e tenta traduzir de novo — criando camadas infinitas.
 *
 * SOLUÇÃO:
 *  1. [onHideOverlay] é chamado ANTES de cada captura — esconde o overlay.
 *  2. Aguarda [overlayHideDelayMs] para o sistema renderizar a tela sem overlay.
 *  3. Captura o frame limpo.
 *  4. [onShowOverlay] é chamado de volta se não houver conteúdo novo.
 *  5. Deduplicação por hash de texto: se o OCR retornar o mesmo texto do
 *     frame anterior, não atualiza o overlay.
 */
class ContinuousCapture(
    private val intervalMs:             Long  = 1_500L,
    private val changeThreshold:        Float = 0.03f,
    private val maxConsecutiveNoChange: Int   = 10,
    private val overlayHideDelayMs:     Long  = 120L   // tempo para o sistema renderizar sem overlay
) {
    private val TAG = "MangaLens_Continuous"

    private var job: Job? = null
    private var lastBitmap: Bitmap? = null

    // Hash do último texto extraído pelo OCR — deduplicação semântica
    @Volatile var lastTextHash: Int = 0
        private set

    private var noChangeCount = 0

    var isRunning: Boolean = false
        private set

    // ─────────────────────────────────────────────
    // INÍCIO
    // ─────────────────────────────────────────────

    /**
     * @param onHideOverlay  Chamado antes de capturar — deve esconder o overlay de tradução
     * @param onCapture      Captura e retorna o bitmap da tela (já sem overlay)
     * @param onChanged      Chamado com o bitmap quando há mudança visual real
     * @param onRestoreOverlay Chamado se o frame for descartado (sem mudança) — restaura overlay
     * @param onIdle         Chamado quando entra em modo ocioso
     */
    fun start(
        scope: CoroutineScope,
        cropRect: Rect?,
        onHideOverlay: suspend () -> Unit,
        onCapture: () -> Bitmap?,
        onChanged: suspend (Bitmap, Rect?) -> Unit,
        onRestoreOverlay: suspend () -> Unit = {},
        onIdle: () -> Unit = {}
    ) {
        stop()
        isRunning     = true
        noChangeCount = 0
        lastBitmap    = null
        lastTextHash  = 0

        Log.d(TAG, "Iniciando — intervalo=${intervalMs}ms")

        job = scope.launch(Dispatchers.IO) {

            // ── Primeiro frame imediato ───────────────────────────────────
            onHideOverlay()
            delay(overlayHideDelayMs)
            val firstFrame = onCapture()
            if (firstFrame != null) {
                val region = cropRegion(firstFrame, cropRect)
                lastBitmap = region
                onChanged(region, cropRect)
            } else {
                onRestoreOverlay()
            }

            // ── Loop principal ────────────────────────────────────────────
            while (isActive && isRunning) {
                delay(intervalMs)

                // 1. Esconde overlay ANTES de capturar
                onHideOverlay()
                delay(overlayHideDelayMs)  // aguarda render sem overlay

                // 2. Captura frame limpo
                val frame = onCapture()
                if (frame == null) {
                    onRestoreOverlay()
                    continue
                }

                val region = cropRegion(frame, cropRect)
                val prev   = lastBitmap
                lastBitmap = region

                // 3. Se não há frame anterior, processa direto
                if (prev == null) {
                    onChanged(region, cropRect)
                    continue
                }

                // 4. Pixel-diff: verifica se houve mudança visual
                val diff = pixelChangeFraction(prev, region)
                Log.d(TAG, "Pixel diff: ${"%.1f".format(diff * 100)}%")

                if (diff >= changeThreshold) {
                    noChangeCount = 0
                    // Mudança visual detectada → deixa o OCR rodar
                    onChanged(region, cropRect)
                } else {
                    noChangeCount++
                    // Sem mudança → restaura o overlay que foi escondido
                    onRestoreOverlay()

                    if (noChangeCount >= maxConsecutiveNoChange) {
                        Log.d(TAG, "Ocioso após $noChangeCount ciclos")
                        withContext(Dispatchers.Main) { onIdle() }
                        delay(4_000L)
                        noChangeCount = 0
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job          = null
        isRunning    = false
        lastBitmap   = null
        lastTextHash = 0
        Log.d(TAG, "Parado")
    }

    // ─────────────────────────────────────────────
    // DEDUPLICAÇÃO SEMÂNTICA
    // ─────────────────────────────────────────────

    /**
     * Compara o texto recém-extraído com o da última captura.
     * Retorna true se for conteúdo NOVO (deve exibir overlay).
     * Retorna false se for o MESMO (descarta, não atualiza overlay).
     */
    fun isNewText(extractedText: String): Boolean {
        val newHash = extractedText.trim().hashCode()
        if (newHash == lastTextHash) {
            Log.d(TAG, "Texto idêntico ao anterior — descartando (hash=$newHash)")
            return false
        }
        lastTextHash = newHash
        return true
    }

    // ─────────────────────────────────────────────
    // UTILITÁRIOS
    // ─────────────────────────────────────────────

    private fun cropRegion(bitmap: Bitmap, crop: Rect?): Bitmap {
        if (crop == null) return bitmap
        val l = crop.left.coerceAtLeast(0)
        val t = crop.top.coerceAtLeast(0)
        val w = crop.width().coerceAtMost(bitmap.width - l).coerceAtLeast(1)
        val h = crop.height().coerceAtMost(bitmap.height - t).coerceAtLeast(1)
        return Bitmap.createBitmap(bitmap, l, t, w, h)
    }

    private fun pixelChangeFraction(a: Bitmap, b: Bitmap, step: Int = 10): Float {
        if (a.width != b.width || a.height != b.height) return 1f
        var changed = 0; var total = 0
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                val pa = a.getPixel(x, y); val pb = b.getPixel(x, y)
                val dr = Math.abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
                val dg = Math.abs(((pa shr 8)  and 0xFF) - ((pb shr 8)  and 0xFF))
                val db = Math.abs((pa and 0xFF) - (pb and 0xFF))
                if (dr > 25 || dg > 25 || db > 25) changed++
                total++
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else changed.toFloat() / total
    }
}