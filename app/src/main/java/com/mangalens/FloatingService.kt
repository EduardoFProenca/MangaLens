package com.mangalens

import android.app.*
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*

class FloatingService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingRoot: View
    private lateinit var floatingBtn: TextView
    private lateinit var badgeLock: TextView
    private lateinit var badgeMode: TextView

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth   = 0
    private var screenHeight  = 0
    private var screenDensity = 0

    enum class CaptureMode { SINGLE, AREA, CONTINUOUS }
    private var captureMode = CaptureMode.SINGLE

    private var lockedRect: Rect? = null
    private var areaLocked        = false
    private var gameModeEnabled   = true

    private val continuousCapture = ContinuousCapture(
        intervalMs         = 1_500L,
        changeThreshold    = 0.03f,
        overlayHideDelayMs = 150L   // ms de espera após esconder o overlay
    )

    private val handler     = Handler(Looper.getMainLooper())
    private var isLongPress = false
    private val LONG_PRESS  = 500L

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Evita múltiplos pipelines OCR simultâneos no modo contínuo
    @Volatile private var continuousOcrInProgress = false

    companion object {
        const val ACTION_START      = "START"
        const val ACTION_STOP       = "STOP"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
        private const val CHANNEL_ID      = "MangaLensChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG             = "MangaLens"
    }

    // ─────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                startForeground(NOTIFICATION_ID, buildNotification())
                setupMediaProjection(code, data)
                showFloatingButton()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        continuousCapture.stop()
        AreaSelectorOverlay.dismiss(windowManager)
        TranslationOverlay.dismiss(windowManager)
        tearDownProjection()
        if (::floatingRoot.isInitialized) runCatching { windowManager.removeView(floatingRoot) }
    }

    // ─────────────────────────────────────────────
    // BOTÃO FLUTUANTE
    // ─────────────────────────────────────────────

    private fun showFloatingButton() {
        floatingRoot = LayoutInflater.from(this).inflate(R.layout.layout_floating_button, null)
        floatingBtn  = floatingRoot.findViewById(R.id.floatingBtn)
        badgeLock    = floatingRoot.findViewById(R.id.badgeLock)
        badgeMode    = floatingRoot.findViewById(R.id.badgeMode)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 300 }

        setupTouchListener(params)
        windowManager.addView(floatingRoot, params)
        updateButtonAppearance()
    }

    private fun updateButtonAppearance() {
        val (icon, colorHex) = when (captureMode) {
            CaptureMode.SINGLE     -> "📷" to "#CC6200EE"
            CaptureMode.AREA       -> "▲"  to "#CCE65100"
            CaptureMode.CONTINUOUS -> "🔄" to "#CC00796B"
        }
        floatingBtn.text = icon
        val bg = floatingBtn.background
        if (bg is GradientDrawable) bg.setColor(Color.parseColor(colorHex))
        else floatingBtn.setBackgroundColor(Color.parseColor(colorHex))

        badgeLock.visibility = if (areaLocked) View.VISIBLE else View.GONE
        badgeMode.text       = if (gameModeEnabled) "🎮" else "📖"
        badgeMode.visibility = View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener(params: WindowManager.LayoutParams) {
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var moved = false

        floatingRoot.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = params.x; iy = params.y
                    tx = event.rawX; ty = event.rawY
                    moved = false; isLongPress = false
                    handler.postDelayed({
                        if (!moved) { isLongPress = true; showModeMenu() }
                    }, LONG_PRESS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - tx).toInt()
                    val dy = (event.rawY - ty).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        moved = true
                        handler.removeCallbacksAndMessages(null)
                        params.x = ix + dx; params.y = iy + dy
                        windowManager.updateViewLayout(floatingRoot, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacksAndMessages(null)
                    if (!moved && !isLongPress) onTap()
                    true
                }
                else -> false
            }
        }
    }

    // ─────────────────────────────────────────────
    // MENU
    // ─────────────────────────────────────────────

    private fun showModeMenu() {
        val singleLabel     = if (captureMode == CaptureMode.SINGLE)     "📷  Tela Cheia ✓"      else "📷  Tela Cheia"
        val areaLabel       = if (captureMode == CaptureMode.AREA)       "▲   Seleção de Área ✓" else "▲   Seleção de Área"
        val continuousLabel = if (captureMode == CaptureMode.CONTINUOUS) "🔄  Tempo Real ✓"       else "🔄  Tempo Real"
        val gameLabel       = if (gameModeEnabled) "🎮  Mini-game [LIGADO] → desligar"
        else                  "📖  Tradução direta [LIGADA] → ligar mini-game"
        val lockLabel       = if (captureMode == CaptureMode.AREA) {
            if (areaLocked) "🔓  Destravar área" else "🔒  Travar área atual"
        } else null

        val options = listOfNotNull(
            singleLabel, areaLabel, continuousLabel,
            "─────────────────",
            gameLabel, lockLabel
        ).toTypedArray()

        handler.post {
            val dlg = android.app.AlertDialog.Builder(
                android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog)
            )
                .setTitle("MangaLens — Configurações")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> switchCaptureTo(CaptureMode.SINGLE)
                        1 -> switchCaptureTo(CaptureMode.AREA)
                        2 -> switchCaptureTo(CaptureMode.CONTINUOUS)
                        3 -> { /* separador */ }
                        4 -> toggleGameMode()
                        5 -> if (lockLabel != null) toggleLock()
                    }
                }.create()
            dlg.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dlg.show()
        }
    }

    private fun toggleGameMode() {
        gameModeEnabled = !gameModeEnabled
        GameOverlayManager.gameModeEnabled = gameModeEnabled
        updateButtonAppearance()
        Toast.makeText(this,
            if (gameModeEnabled) "🎮 Mini-game ligado" else "📖 Tradução direta ligada",
            Toast.LENGTH_SHORT).show()
    }

    private fun switchCaptureTo(mode: CaptureMode) {
        if (captureMode == CaptureMode.CONTINUOUS && mode != CaptureMode.CONTINUOUS) {
            continuousCapture.stop()
            continuousOcrInProgress = false
            // Restaura overlay se estava escondido
            TranslationOverlay.restore()
        }
        captureMode = mode
        when (mode) {
            CaptureMode.SINGLE -> {
                areaLocked = false; lockedRect = null
                updateButtonAppearance()
                Toast.makeText(this, "📷 Tela cheia", Toast.LENGTH_SHORT).show()
            }
            CaptureMode.AREA -> {
                updateButtonAppearance()
                Toast.makeText(this, "▲ Seleção de área", Toast.LENGTH_SHORT).show()
            }
            CaptureMode.CONTINUOUS -> {
                updateButtonAppearance()
                Toast.makeText(this, "🔄 Tempo Real — monitorando...", Toast.LENGTH_SHORT).show()
                startContinuousMode()
            }
        }
    }

    private fun toggleLock() {
        if (areaLocked) {
            areaLocked = false; lockedRect = null
            updateButtonAppearance()
            Toast.makeText(this, "🔓 Área destravada", Toast.LENGTH_SHORT).show()
        } else {
            if (lockedRect != null) {
                areaLocked = true
                updateButtonAppearance()
                Toast.makeText(this, "🔒 Área travada!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Selecione uma área primeiro", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─────────────────────────────────────────────
    // TOQUE SIMPLES
    // ─────────────────────────────────────────────

    private fun onTap() {
        when (captureMode) {
            CaptureMode.SINGLE -> captureAndProcess(cropRect = null, isFullScreen = true)
            CaptureMode.AREA -> {
                if (areaLocked && lockedRect != null) captureAndProcess(cropRect = lockedRect, isFullScreen = false)
                else openAreaSelector()
            }
            CaptureMode.CONTINUOUS -> {
                if (continuousCapture.isRunning) {
                    continuousCapture.stop()
                    continuousOcrInProgress = false
                    TranslationOverlay.restore()
                    Toast.makeText(this, "⏸ Pausado", Toast.LENGTH_SHORT).show()
                } else {
                    startContinuousMode()
                    Toast.makeText(this, "▶️ Retomado", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // SELETOR DE ÁREA
    // ─────────────────────────────────────────────

    private fun openAreaSelector() {
        AreaSelectorOverlay.show(
            context        = this,
            windowManager  = windowManager,
            onAreaSelected = { rect ->
                lockedRect = rect
                captureAndProcess(cropRect = rect, isFullScreen = false)
            },
            onCancel = { Toast.makeText(this, "Seleção cancelada", Toast.LENGTH_SHORT).show() }
        )
    }

    // ─────────────────────────────────────────────
    // MODO CONTÍNUO
    // O ciclo completo por iteração:
    //  1. hide overlay
    //  2. aguarda render
    //  3. captura frame limpo
    //  4. pixel-diff
    //  5. se mudou → OCR → dedup texto → mostra overlay
    //  6. se não mudou → restore overlay
    // ─────────────────────────────────────────────

    private fun startContinuousMode() {
        rebuildImageReader()
        continuousOcrInProgress = false

        continuousCapture.start(
            scope        = serviceScope,
            cropRect     = lockedRect,

            // Passo 1: esconde o overlay ANTES de capturar
            onHideOverlay = {
                withContext(Dispatchers.Main) {
                    TranslationOverlay.hide()
                    GameOverlayManager.hideForCapture()
                }
            },

            // Passo 2: captura frame limpo (overlay já escondido)
            onCapture = { captureScreenDirect() },

            // Passo 3: há mudança visual → roda OCR com dedup de texto
            onChanged = { bitmap, crop ->
                if (continuousOcrInProgress) {
                    // OCR anterior ainda em curso → restaura overlay e pula
                    withContext(Dispatchers.Main) {
                        TranslationOverlay.restore()
                        GameOverlayManager.restoreForCapture()
                    }
                    return@start
                }

                continuousOcrInProgress = true

                withContext(Dispatchers.Main) {
                    OcrProcessor.process(this@FloatingService, bitmap, null) { results ->
                        continuousOcrInProgress = false

                        if (results.isEmpty()) {
                            // Nenhum texto → restaura o que estava antes
                            TranslationOverlay.restore()
                            GameOverlayManager.restoreForCapture()
                            return@process
                        }

                        // Deduplicação semântica: hash do texto em inglês
                        val combinedText = results.joinToString("|") { it.originalText.trim() }
                        if (!continuousCapture.isNewText(combinedText)) {
                            // Mesmo texto da última captura → restaura overlay anterior
                            TranslationOverlay.restore()
                            GameOverlayManager.restoreForCapture()
                            return@process
                        }

                        // Conteúdo novo → fecha overlay anterior e abre o novo
                        Log.d(TAG, "Novo conteúdo — atualizando overlay")
                        showResults(results, crop, isFullScreen = false)
                    }
                }
            },

            // Passo 4: sem mudança visual → restaura overlay
            onRestoreOverlay = {
                withContext(Dispatchers.Main) {
                    TranslationOverlay.restore()
                    GameOverlayManager.restoreForCapture()
                }
            },

            onIdle = { Log.d(TAG, "Contínuo: ocioso") }
        )
    }

    // ─────────────────────────────────────────────
    // PIPELINE PONTUAL
    // ─────────────────────────────────────────────

    private fun captureAndProcess(cropRect: Rect?, isFullScreen: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { rebuildImageReader() }

            val deadline = System.currentTimeMillis() + 4_000L
            while (imageReader?.let { it.acquireLatestImage()?.also { img -> img.close() } } == null
                && System.currentTimeMillis() < deadline) {
                delay(40)
            }
            delay(120)

            val bitmap: Bitmap = captureScreenDirect() ?: run {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloatingService, "❌ Erro ao capturar", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            saveDebugBitmap(bitmap)

            withContext(Dispatchers.Main) {
                OcrProcessor.process(this@FloatingService, bitmap, cropRect) { results ->
                    if (results.isEmpty()) {
                        Toast.makeText(this@FloatingService, "🔍 Nenhum texto encontrado", Toast.LENGTH_SHORT).show()
                    } else {
                        showResults(results, cropRect, isFullScreen)
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // EXIBIÇÃO DE RESULTADOS
    // ─────────────────────────────────────────────

    private fun showResults(results: List<TextResult>, cropRect: Rect?, isFullScreen: Boolean) {
        if (gameModeEnabled) {
            GameOverlayManager.gameModeEnabled = true
            GameOverlayManager.show(
                context        = this,
                windowManager  = windowManager,
                results        = results,
                filterSystemUi = isFullScreen
            )
        } else {
            // Fecha o overlay anterior antes de abrir o novo — evita sobreposição
            TranslationOverlay.dismiss(windowManager)
            TranslationOverlay.show(
                context       = this,
                windowManager = windowManager,
                results       = results,
                screenWidth   = screenWidth,
                screenHeight  = screenHeight,
                cropRect      = cropRect
            )
        }
    }

    // ─────────────────────────────────────────────
    // MEDIAPROJECTION
    // ─────────────────────────────────────────────

    private fun setupMediaProjection(resultCode: Int, data: Intent?) {
        if (data == null) return
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, data)
        val m = resources.displayMetrics
        screenWidth = m.widthPixels; screenHeight = m.heightPixels; screenDensity = m.densityDpi
        rebuildImageReader()
    }

    private fun rebuildImageReader() {
        virtualDisplay?.release(); imageReader?.close()
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({}, handler)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MangaLens", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        Log.d(TAG, "VirtualDisplay: ${screenWidth}x${screenHeight}")
    }

    private fun captureScreenDirect(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        return try {
            var c = reader.acquireLatestImage()
            while (c != null) { image?.close(); image = c; c = reader.acquireLatestImage() }
            val img = image ?: return null
            val plane  = img.planes[0]
            val rowPad = plane.rowStride - plane.pixelStride * img.width
            val bmp = Bitmap.createBitmap(
                img.width + rowPad / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            if (rowPad == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
        } catch (e: Exception) { Log.e(TAG, "captureScreen: ${e.message}"); null }
        finally { image?.close() }
    }

    private fun tearDownProjection() {
        mediaProjection?.stop(); virtualDisplay?.release(); imageReader?.close()
        mediaProjection = null; virtualDisplay = null; imageReader = null
    }

    private fun saveDebugBitmap(bmp: Bitmap) {
        runCatching {
            val f = java.io.File(getExternalFilesDir(null), "debug_capture.png")
            java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
    }

    // ─────────────────────────────────────────────
    // NOTIFICAÇÃO
    // ─────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "MangaLens Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getService(this, 0,
            Intent(this, FloatingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MangaLens ativo")
            .setContentText("Toque longo no botão para configurar")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_delete, "Parar", pi)
            .build()
    }
}