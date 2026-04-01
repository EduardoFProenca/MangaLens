// app/src/main/java/com/mangalens/MainActivity.kt
package com.mangalens

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startFloatingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Permissão negada", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            checkOverlayPermissionAndStart()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            startService(Intent(this, FloatingService::class.java).apply {
                action = FloatingService.ACTION_STOP
            })
            Toast.makeText(this, "MangaLens parado!", Toast.LENGTH_SHORT).show()
        }

        // Abre a Biblioteca de capturas
        findViewById<Button>(R.id.btnLibrary).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }

        // Baixa modelos EN→PT e JA→PT em background
        OcrProcessor.downloadModelsIfNeeded {
            Toast.makeText(this, "✅ Modelos prontos (EN + JA)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkOverlayPermissionAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            Toast.makeText(this, "Ative 'Aparecer sobre outros apps' e volte aqui", Toast.LENGTH_LONG).show()
        } else {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    private fun startFloatingService(resultCode: Int, data: Intent) {
        startForegroundService(Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_START
            putExtra(FloatingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(FloatingService.EXTRA_RESULT_DATA, data)
        })
        moveTaskToBack(true)
    }
}