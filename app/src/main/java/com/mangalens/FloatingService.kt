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

class
FloatingService : LifecycleService() {

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

    // Dimensões reais do último bitmap capturado.
    // Usadas para converter coordenadas do OCR → coordenadas da tela.
    private var lastBitmapWidth  = 0
    private var lastBitmapHeight = 0

    enum class CaptureMode { SINGLE, AREA, CONTINUOUS, GOOGLE_LENS }
    private var captureMode = CaptureMode.SINGLE

    private var lockedRect: Rect? = null
    private var areaLocked        = false
    private var gameModeEnabled   = true

    private val continuousCapture = ContinuousCapture(
        intervalMs         = 1_500L,
        changeThreshold    = 0.03f,
        overlayHideDelayMs = 150L
    )

    private val handler     = Handler(Looper.getMainLooper())
    private var isLongPress = false
    private val LONG_PRESS  = 500L

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
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
            CaptureMode.SINGLE      -> "📷" to "#CC6200EE"
            CaptureMode.AREA        -> "▲"  to "#CCE65100"
            CaptureMode.CONTINUOUS  -> "🔄" to "#CC00796B"
            CaptureMode.GOOGLE_LENS -> "🔍" to "#CC1565C0"
        }
        floatingBtn.text = icon
        val bg = floatingBtn.background
        if (bg is GradientDrawable) bg.setColor(Color.parseColor(colorHex))
        else floatingBtn.setBackgroundColor(Color.parseColor(colorHex))

        badgeLock.visibility = if (areaLocked) View.VISIBLE else View.GONE
        badgeMode.text       = if (gameModeEnabled) "🎮" else "📖"
        badgeMode.visibility = if (captureMode == CaptureMode.GOOGLE_LENS) View.GONE else View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener(params: WindowManager.LayoutParams) {
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var moved = false

        floatingRoot.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = params.x; iy = params.y; tx = event.rawX; ty = event.rawY
                    moved = false; isLongPress = false
                    handler.postDelayed({ if (!moved) { isLongPress = true; showModeMenu() } }, LONG_PRESS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - tx).toInt(); val dy = (event.rawY - ty).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        moved = true; handler.removeCallbacksAndMessages(null)
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
        fun label(mode: CaptureMode, text: String) = if (captureMode == mode) "$text ✓" else text

        val gameLabel = if (gameModeEnabled) "🎮  Mini-game [LIGADO] → desligar"
        else "📖  Tradução direta [LIGADA] → ligar mini-game"
        val lockLabel = if (captureMode == CaptureMode.AREA) {
            if (areaLocked) "🔓  Destravar área" else "🔒  Travar área atual"
        } else null

        val options = listOfNotNull(
            label(CaptureMode.SINGLE,      "📷  Tela Cheia"),
            label(CaptureMode.AREA,        "▲   Seleção de Área"),
            label(CaptureMode.CONTINUOUS,  "🔄  Tempo Real"),
            label(CaptureMode.GOOGLE_LENS, "🔍  Google Lens"),
            "─────────────────",
            gameLabel,
            lockLabel
        ).toTypedArray()

        handler.post {
            val dlg = android.app.AlertDialog.Builder(
                android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog)
            )
                .setTitle("MangaLens — Modo de captura")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> switchCaptureTo(CaptureMode.SINGLE)
                        1 -> switchCaptureTo(CaptureMode.AREA)
                        2 -> switchCaptureTo(CaptureMode.CONTINUOUS)
                        3 -> switchCaptureTo(CaptureMode.GOOGLE_LENS)
                        4 -> { /* separador */ }
                        5 -> toggleGameMode()
                        6 -> if (lockLabel != null) toggleLock()
                    }
                }.create()
            dlg.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dlg.show()
        }
    }

    // ─────────────────────────────────────────────
    // TROCA DE MODO
    // ─────────────────────────────────────────────

    private fun switchCaptureTo(mode: CaptureMode) {
        if (captureMode == CaptureMode.CONTINUOUS && mode != CaptureMode.CONTINUOUS) {
            continuousCapture.stop(); continuousOcrInProgress = false; TranslationOverlay.restore()
        }
        captureMode = mode; updateButtonAppearance()
        val msg = when (mode) {
            CaptureMode.SINGLE      -> "📷 Tela cheia — toque para capturar"
            CaptureMode.AREA        -> "▲ Seleção de área — toque para desenhar"
            CaptureMode.CONTINUOUS  -> "🔄 Tempo Real — iniciando..."
            CaptureMode.GOOGLE_LENS -> "🔍 Google Lens ativo — toque para traduzir"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        if (mode == CaptureMode.SINGLE) { areaLocked = false; lockedRect = null }
        if (mode == CaptureMode.CONTINUOUS) startContinuousMode()
    }

    private fun toggleGameMode() {
        gameModeEnabled = !gameModeEnabled
        GameOverlayManager.gameModeEnabled = gameModeEnabled
        updateButtonAppearance()
        Toast.makeText(this, if (gameModeEnabled) "🎮 Mini-game ligado" else "📖 Tradução direta ligada",
            Toast.LENGTH_SHORT).show()
    }

    private fun toggleLock() {
        if (areaLocked) {
            areaLocked = false; lockedRect = null; updateButtonAppearance()
            Toast.makeText(this, "🔓 Área destravada", Toast.LENGTH_SHORT).show()
        } else {
            if (lockedRect != null) {
                areaLocked = true; updateButtonAppearance()
                Toast.makeText(this, "🔒 Área travada!", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, "Selecione uma área primeiro", Toast.LENGTH_SHORT).show()
        }
    }

    // ─────────────────────────────────────────────
    // TOQUE SIMPLES
    // ─────────────────────────────────────────────

    private fun onTap() {
        when (captureMode) {
            CaptureMode.SINGLE -> captureAndProcess(cropRect = null, isFullScreen = true)
            CaptureMode.AREA   -> {
                if (areaLocked && lockedRect != null) captureAndProcess(cropRect = lockedRect, isFullScreen = false)
                else openAreaSelector()
            }
            CaptureMode.CONTINUOUS -> {
                if (continuousCapture.isRunning) {
                    continuousCapture.stop(); continuousOcrInProgress = false
                    TranslationOverlay.restore()
                    Toast.makeText(this, "⏸ Pausado", Toast.LENGTH_SHORT).show()
                } else {
                    startContinuousMode()
                    Toast.makeText(this, "▶️ Retomado", Toast.LENGTH_SHORT).show()
                }
            }
            CaptureMode.GOOGLE_LENS -> triggerGoogleLens()
        }
    }

    // ─────────────────────────────────────────────
    // GOOGLE LENS
    // ─────────────────────────────────────────────

    private fun triggerGoogleLens() {
        serviceScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                TranslationOverlay.hide(); GameOverlayManager.hideForCapture()
            }
            delay(180L)
            val bitmap = captureScreenDirect()
            withContext(Dispatchers.Main) {
                TranslationOverlay.restore(); GameOverlayManager.restoreForCapture()
            }
            if (bitmap == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloatingService, "❌ Não foi possível capturar", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            withContext(Dispatchers.Main) { GoogleLensHelper.openWithScreenshot(this@FloatingService, bitmap) }
        }
    }

    // ─────────────────────────────────────────────
    // SELETOR DE ÁREA
    // ─────────────────────────────────────────────

    private fun openAreaSelector() {
        AreaSelectorOverlay.show(
            context        = this,
            windowManager  = windowManager,
            onAreaSelected = { rect -> lockedRect = rect; captureAndProcess(cropRect = rect, isFullScreen = false) },
            onCancel       = { Toast.makeText(this, "Seleção cancelada", Toast.LENGTH_SHORT).show() }
        )
    }

    // ─────────────────────────────────────────────
    // MODO CONTÍNUO
    // ─────────────────────────────────────────────

    private fun startContinuousMode() {
        rebuildImageReader()
        continuousOcrInProgress = false

        continuousCapture.start(
            scope         = serviceScope,
            cropRect      = lockedRect,
            onHideOverlay = { withContext(Dispatchers.Main) { TranslationOverlay.hide(); GameOverlayManager.hideForCapture() } },
            onCapture     = { captureScreenDirect() },
            onChanged     = { bitmap, crop ->
                if (continuousOcrInProgress) {
                    withContext(Dispatchers.Main) { TranslationOverlay.restore(); GameOverlayManager.restoreForCapture() }
                    return@start
                }
                continuousOcrInProgress = true

                // Registra dimensões do bitmap para escala correta
                lastBitmapWidth  = bitmap.width
                lastBitmapHeight = bitmap.height

                withContext(Dispatchers.Main) {
                    OcrProcessor.process(this@FloatingService, bitmap, crop) { results ->
                        continuousOcrInProgress = false
                        if (results.isEmpty()) {
                            TranslationOverlay.restore(); GameOverlayManager.restoreForCapture(); return@process
                        }
                        val combinedText = results.joinToString("|") { it.originalText.trim() }
                        if (!continuousCapture.isNewText(combinedText)) {
                            TranslationOverlay.restore(); GameOverlayManager.restoreForCapture(); return@process
                        }
                        showResults(results, crop, isFullScreen = false, forceTranslationMode = true)
                    }
                }
            },
            onRestoreOverlay = { withContext(Dispatchers.Main) { TranslationOverlay.restore(); GameOverlayManager.restoreForCapture() } },
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
                && System.currentTimeMillis() < deadline) { delay(40) }
            delay(120)

            val bitmap: Bitmap = captureScreenDirect() ?: run {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloatingService, "❌ Erro ao capturar", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // Registra dimensões reais do bitmap
            lastBitmapWidth  = bitmap.width
            lastBitmapHeight = bitmap.height

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

    private fun showResults(
        results: List<TextResult>,
        cropRect: Rect?,
        isFullScreen: Boolean,
        forceTranslationMode: Boolean = false
    ) {
        if (forceTranslationMode || !gameModeEnabled) {
            TranslationOverlay.dismiss(windowManager)
            TranslationOverlay.show(
                context       = this,
                windowManager = windowManager,
                results       = results,
                screenWidth   = screenWidth,
                screenHeight  = screenHeight,
                cropRect      = cropRect,
                // ← passa as dimensões reais do bitmap para escala correta
                bitmapWidth   = if (cropRect != null) cropRect.width()  else lastBitmapWidth.takeIf { it > 0 } ?: screenWidth,
                bitmapHeight  = if (cropRect != null) cropRect.height() else lastBitmapHeight.takeIf { it > 0 } ?: screenHeight
            )
        } else {
            GameOverlayManager.gameModeEnabled = true
            GameOverlayManager.show(
                context        = this,
                windowManager  = windowManager,
                results        = results,
                filterSystemUi = isFullScreen
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