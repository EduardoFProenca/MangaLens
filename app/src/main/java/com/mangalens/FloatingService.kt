package com.mangalens

import android.app.*
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*

class FloatingService : LifecycleService() {

    // ─── WindowManager: responsável por desenhar na tela ───
    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: View

    // ─── MediaProjection: responsável por capturar a tela ───
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // ─── Controle de toque longo ───
    private val handler = Handler(Looper.getMainLooper())
    private var isLongPress = false
    private val LONG_PRESS_DURATION = 500L

    // ─── Coroutine para não travar a UI ───
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
        private const val CHANNEL_ID = "MangaLensChannel"
        private const val NOTIFICATION_ID = 1
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
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mediaProjection?.stop()
        virtualDisplay?.release()
        imageReader?.close()
        if (::floatingButton.isInitialized) {
            windowManager.removeView(floatingButton)
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
                    // CORREÇÃO APLICADA AQUI:
                    if (!moved && !isLongPress) {
                        Toast.makeText(
                            this@FloatingService,
                            "📸 Capturando...",
                            Toast.LENGTH_SHORT
                        ).show()
                        captureAndProcess()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun showRadialMenu() {
        Toast.makeText(
            this, "Menu: Tempo Real | Área | Tela Cheia", Toast.LENGTH_SHORT
        ).show()
    }

    // ─────────────────────────────────────────────
    // CAPTURA DE TELA (MediaProjection)
    // ─────────────────────────────────────────────

    private fun setupMediaProjection(resultCode: Int, data: Intent?) {
        if (data == null) return

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // Usamos PixelFormat.RGBA_8888.
        // Se o Android Studio continuar sublinhando em vermelho, você pode usar o número 1
        // que é o valor real de RGBA_8888, mas o código abaixo deve funcionar:
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MangaLensCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )
    }

    private fun captureAndProcess() {
        serviceScope.launch(Dispatchers.IO) {
            // Aguarda 300ms para a tela estabilizar após o toque
            Thread.sleep(300)

            var bitmap: android.graphics.Bitmap? = null
            repeat(15) {
                bitmap = captureScreen()
                if (bitmap != null) return@repeat
                Thread.sleep(100)
            }

            val finalBitmap = bitmap ?: run {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@FloatingService, "❌ Nenhum frame", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

// DEBUG: salva o bitmap para ver o que foi capturado
            withContext(Dispatchers.IO) {
                try {
                    val file = java.io.File(getExternalFilesDir(null), "debug_capture.png")
                    java.io.FileOutputStream(file).use { out ->
                        finalBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
                    }
                    android.util.Log.d("MangaLens", "Screenshot salvo em: ${file.absolutePath}")
                    android.util.Log.d("MangaLens", "Tamanho: ${finalBitmap.width}x${finalBitmap.height}")
                } catch (e: Exception) {
                    android.util.Log.e("MangaLens", "Erro ao salvar: ${e.message}")
                }
                // Salva na pasta pública de Downloads do celular
                val file = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "debug_capture.png"
                )
            }

            withContext(Dispatchers.Main) {
                OcrProcessor.process(this@FloatingService, finalBitmap) { results ->
                    if (results.isEmpty()) {
                        android.widget.Toast.makeText(
                            this@FloatingService,
                            "🔍 Nenhum texto encontrado",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        GameOverlayManager.show(this@FloatingService, windowManager, results)
                    }
                }
            }
        }
    }

    private fun captureScreen(): android.graphics.Bitmap? {
        val reader = imageReader ?: return null

        // Descarta todos os frames antigos, pega só o mais recente
        var image: android.media.Image? = null
        var latest = reader.acquireLatestImage()
        while (latest != null) {
            image?.close() // fecha o anterior
            image = latest
            latest = reader.acquireLatestImage()
        }

        if (image == null) return null

        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = android.graphics.Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } finally {
            image.close()
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
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MangaLens ativo")
            .setContentText("Toque no botão flutuante para traduzir")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_delete, "Parar", stopPending)
            .build()
    }
}