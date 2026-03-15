package com.mangalens

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.*

/**
 * Gerencia o modo de captura contínua.
 *
 * Fluxo:
 *  1. A cada [intervalMs] ms captura um frame
 *  2. Calcula a diferença média de pixels vs. o frame anterior
 *  3. Se a diferença > [changeThreshold], aciona o OCR
 *  4. Para de processar se [maxConsecutiveNoChange] frames consecutivos
 *     não mostrarem mudança (economiza bateria)
 */
class ContinuousCapture(
    private val intervalMs:              Long  = 1_500L,   // intervalo entre capturas
    private val changeThreshold:         Float = 0.04f,    // 4% de pixels diferentes
    private val maxConsecutiveNoChange:  Int   = 10        // para após 15 s sem mudança
) {
    private val TAG = "MangaLens_Continuous"

    private var job: Job? = null
    private var lastBitmap: Bitmap? = null
    private var noChangeCount = 0

    var isRunning: Boolean = false
        private set

    // ─────────────────────────────────────────────
    // CONTROLE
    // ─────────────────────────────────────────────

    /**
     * Inicia a captura contínua.
     *
     * @param cropRect    Região a monitorar (null = tela inteira)
     * @param onCapture   Função chamada para capturar o frame atual
     * @param onChanged   Chamado com o bitmap quando a tela muda
     * @param onIdle      Chamado quando o monitor entra em modo ocioso
     */
    fun start(
        scope: CoroutineScope,
        cropRect: Rect?,
        onCapture: () -> Bitmap?,
        onChanged: (Bitmap, Rect?) -> Unit,
        onIdle: () -> Unit = {}
    ) {
        stop()
        isRunning     = true
        noChangeCount = 0
        lastBitmap    = null

        Log.d(TAG, "Modo contínuo iniciado (intervalo=${intervalMs}ms, threshold=$changeThreshold)")

        job = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                delay(intervalMs)

                val frame = onCapture() ?: continue

                // Recorta se necessário
                val region = if (cropRect != null) {
                    Bitmap.createBitmap(
                        frame,
                        cropRect.left.coerceAtLeast(0),
                        cropRect.top.coerceAtLeast(0),
                        cropRect.width().coerceAtMost(frame.width  - cropRect.left.coerceAtLeast(0)),
                        cropRect.height().coerceAtMost(frame.height - cropRect.top.coerceAtLeast(0))
                    )
                } else frame

                val prev = lastBitmap
                lastBitmap = region

                if (prev == null) continue   // primeiro frame, sem comparação

                val diff = computeChangeFraction(prev, region)
                Log.d(TAG, "Diferença de frame: ${"%.2f".format(diff * 100)}%")

                if (diff >= changeThreshold) {
                    noChangeCount = 0
                    Log.d(TAG, "Mudança detectada — acionando OCR")
                    withContext(Dispatchers.Main) {
                        onChanged(region, cropRect)
                    }
                } else {
                    noChangeCount++
                    if (noChangeCount >= maxConsecutiveNoChange) {
                        Log.d(TAG, "Sem mudança por ${noChangeCount} ciclos — modo ocioso")
                        withContext(Dispatchers.Main) { onIdle() }
                        // Reduz frequência para economizar bateria (1 check a cada 5 s)
                        delay(5_000L - intervalMs)
                        noChangeCount = 0
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job       = null
        isRunning = false
        lastBitmap = null
        Log.d(TAG, "Modo contínuo parado")
    }

    // ─────────────────────────────────────────────
    // DIFERENÇA DE PIXELS (amostragem rápida)
    // ─────────────────────────────────────────────

    /**
     * Compara dois bitmaps por amostragem (a cada [step] pixels).
     * Retorna a fração de pixels com diferença perceptível.
     *
     * É rápido (~2 ms) porque não analisa todos os pixels.
     */
    private fun computeChangeFraction(a: Bitmap, b: Bitmap, step: Int = 8): Float {
        // Garante mesmas dimensões para comparação
        if (a.width != b.width || a.height != b.height) return 1f

        var changed = 0
        var total   = 0

        val w = a.width
        val h = a.height

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pa = a.getPixel(x, y)
                val pb = b.getPixel(x, y)

                // Diferença absoluta por canal (R, G, B)
                val dr = Math.abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
                val dg = Math.abs(((pa shr 8)  and 0xFF) - ((pb shr 8)  and 0xFF))
                val db = Math.abs((pa           and 0xFF) - (pb           and 0xFF))

                // Limiar de 30/255 (~12%) por canal para considerar "mudou"
                if (dr > 30 || dg > 30 || db > 30) changed++
                total++
                x += step
            }
            y += step
        }

        return if (total == 0) 0f else changed.toFloat() / total.toFloat()
    }
}