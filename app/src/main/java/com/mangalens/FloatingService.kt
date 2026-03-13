package com.mangalens

import android.app.*
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*

class FloatingService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: View

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Dimensões da tela — preenchidas em setupMediaProjection
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val handler = Handler(Looper.getMainLooper())
    private var isLongPress = false
    private val LONG_PRESS_DURATION = 500L

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Flag: indica que um novo frame já chegou após registrarmos o listener
    @Volatile private var frameAvailable = false

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
        private const val CHANNEL_ID = "MangaLensChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "MangaLens"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                startForeground(NOTIFICATION_ID, buildNotification())
                setupMediaProjection(resultCode, resultData)
                showFloatingButton()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        tearDownProjection()
        if (::floatingButton.isInitialized) {
            runCatching { windowManager.removeView(floatingButton) }
        }
    }

    // ─────────────────────────────────────────────
    // BOTÃO FLUTUANTE
    // ─────────────────────────────────────────────

    private fun showFloatingButton() {
        floatingButton = LayoutInflater.from(this)
            .inflate(R.layout.layout_floating_button, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        setupButtonTouchListener(params)
        windowManager.addView(floatingButton, params)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupButtonTouchListener(params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        floatingButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    isLongPress = false

                    handler.postDelayed({
                        if (!moved) {
                            isLongPress = true
                            showRadialMenu()
                        }
                    }, LONG_PRESS_DURATION)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        moved = true
                        handler.removeCallbacksAndMessages(null)
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(floatingButton, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacksAndMessages(null)
                    if (!moved && !isLongPress) {
                        Toast.makeText(this@FloatingService, "📸 Capturando...", Toast.LENGTH_SHORT).show()
                        captureAndProcess()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun showRadialMenu() {
        Toast.makeText(this, "Menu: Tempo Real | Área | Tela Cheia", Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────────
    // MEDIAPROJECTION
    // ─────────────────────────────────────────────

    private fun setupMediaProjection(resultCode: Int, data: Intent?) {
        if (data == null) return

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        screenWidth  = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        rebuildImageReader()
    }

    /**
     * Cria (ou recria) o ImageReader e o VirtualDisplay.
     * Chamado na inicialização e a cada nova captura para garantir frames frescos.
     */
    private fun rebuildImageReader() {
        // Libera recursos anteriores
        virtualDisplay?.release()
        imageReader?.close()

        // MAX_IMAGES = 2 é suficiente; mais não ajuda e pode causar buffer stall
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        // Registra listener: sinaliza quando um frame novo chegar
        frameAvailable = false
        imageReader?.setOnImageAvailableListener({ _ ->
            frameAvailable = true
        }, handler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MangaLensCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )

        Log.d(TAG, "VirtualDisplay criado: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")
    }

    private fun tearDownProjection() {
        mediaProjection?.stop()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection = null
        virtualDisplay = null
        imageReader = null
    }

    // ─────────────────────────────────────────────
    // CAPTURA + PROCESSO
    // ─────────────────────────────────────────────

    private fun captureAndProcess() {
        serviceScope.launch(Dispatchers.IO) {
            // 1. Recria o VirtualDisplay para forçar um frame totalmente novo.
            //    Isso evita receber um frame "velho" do buffer.
            withContext(Dispatchers.Main) { rebuildImageReader() }

            // 2. Aguarda o listener sinalizar que um frame chegou (até 3 s)
            val frameTimeout = System.currentTimeMillis() + 3_000
            while (!frameAvailable && System.currentTimeMillis() < frameTimeout) {
                Thread.sleep(50)
            }

            if (!frameAvailable) {
                Log.w(TAG, "Timeout aguardando frame")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloatingService, "❌ Nenhum frame disponível", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // 3. Pequena pausa extra para garantir que o frame representa a tela atual
            Thread.sleep(100)

            val bitmap = captureScreen()
            if (bitmap == null) {
                Log.w(TAG, "captureScreen() retornou null mesmo com frameAvailable=true")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloatingService, "❌ Falha ao ler frame", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            Log.d(TAG, "Frame capturado: ${bitmap.width}x${bitmap.height}")

            // 4. Salva debug (opcional — remova em produção)
            saveDebugBitmap(bitmap)

            // 5. OCR + Tradução
            withContext(Dispatchers.Main) {
                OcrProcessor.process(this@FloatingService, bitmap) { results ->
                    if (results.isEmpty()) {
                        Toast.makeText(this@FloatingService, "🔍 Nenhum texto encontrado", Toast.LENGTH_SHORT).show()
                    } else {
                        GameOverlayManager.show(this@FloatingService, windowManager, results)
                    }
                }
            }
        }
    }

    /**
     * Lê o frame mais recente do ImageReader.
     * Drena todos os frames acumulados e usa apenas o último.
     */
    private fun captureScreen(): Bitmap? {
        val reader = imageReader ?: return null
        frameAvailable = false   // reset para próxima captura

        var image: Image? = null
        try {
            // Drena o buffer: descarta frames antigos e fica com o mais novo
            var candidate = reader.acquireLatestImage()
            while (candidate != null) {
                image?.close()
                image = candidate
                candidate = reader.acquireLatestImage()
            }
            val currentImage = image ?: return null

            val plane     = currentImage.planes[0]
            val buffer    = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride   = plane.rowStride
            val rowPadding  = rowStride - pixelStride * currentImage.width

            val bitmapWidth = currentImage.width + rowPadding / pixelStride

            val bitmap = Bitmap.createBitmap(
                bitmapWidth,
                currentImage.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Se houver padding na linha, recorta para as dimensões reais
            return if (rowPadding == 0) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, 0, 0, currentImage.width, currentImage.height)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ler frame: ${e.message}")
            return null
        } finally {
            image?.close()
        }
    }

    private fun saveDebugBitmap(bitmap: Bitmap) {
        try {
            val file = java.io.File(getExternalFilesDir(null), "debug_capture.png")
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            Log.d(TAG, "Debug salvo em: ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar debug: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────
    // NOTIFICAÇÃO
    // ─────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "MangaLens Overlay",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MangaLens ativo")
            .setContentText("Toque no botão flutuante para traduzir")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_delete, "Parar", stopPending)
            .build()
    }
}