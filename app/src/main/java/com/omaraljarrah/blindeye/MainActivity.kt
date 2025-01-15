package com.omaraljarrah.blindeye

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope
import com.omaraljarrah.blindeye.ui.camera.CameraController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.Exception

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraController: CameraController

    companion object {
        // If you do NOT need Manage All Files, set this to false
        private const val REQUIRE_MANAGE_ALL_FILES = false

        private val requiredPermissions = setOf(
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
    }

    /**
     *  Multiple-permissions launcher:
     *  - Camera
     *  - Granular media (images, video, audio) on Android 13+
     *  If any permission is denied, we exit the app.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->

            // 1) Filter out only the required permissions
            val deniedRequired = result.filter { (permission, isGranted) ->
                permission in requiredPermissions && !isGranted
            }

            if (deniedRequired.isNotEmpty()) {
                // At least one required permission is denied
                Toast.makeText(this, "All required permissions must be granted.", Toast.LENGTH_SHORT).show()
                finish() // Exits the activity (and the app, if this is your main Activity)
            } else {
                // All required permissions granted
                // (Optional permissions might be denied, but we ignore that here)
                startCameraInCoroutine()
            }
        }


    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createUI())

        cameraController = CameraController(
            activity = this,
            lifecycleOwner = this,
            previewView = previewView
        )

        CACHE_DIR = cacheDir

        // Request everything we need
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // If permissions are already granted, just start camera
            if (allDangerousPermissionsGranted()) {
                startCameraInCoroutine()
            } else {
                requestAllPermissions()
            }
        } else {
            // Below T, your old approach or handle accordingly
            // For example, just request CAMERA as you did before:
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                startCameraInCoroutine()
            } else {
                // We can still use the original single-permission approach or adapt as needed
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        TextToSpeechService.mediaPlayer = MediaPlayer(this)

        // Start scheduler with lifecycleScope (auto-cancel on destroy)
        println("starting DescriptionScheduler")
        DescriptionScheduler.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel the job if you want explicit control (optional if using lifecycleScope)
        DescriptionScheduler.stop()
    }

    /**
     * Check again in onResume if "Manage All Files" permission was granted
     * after coming back from the Settings screen.
     */
    override fun onResume() {
        super.onResume()
    }

    /**
     * A single function to request all necessary permissions on Android 13+.
     * - CAMERA
     * - READ_MEDIA_IMAGES / READ_MEDIA_VIDEO / READ_MEDIA_AUDIO
     * - Manage All Files (opens Settings if not granted)
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Camera
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }

        // Granular media permissions (Android 13+). Add only what you actually need:
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
        }
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
        }

        if (checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
        }

        // Manage All Files (special). If you want to require it:
        if (REQUIRE_MANAGE_ALL_FILES && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

        // Request all dangerous permissions at once
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // All are already granted, let's start the camera
            startCameraInCoroutine()
        }
    }

    /**
     * Check if *all* required dangerous permissions are already granted.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun allDangerousPermissionsGranted(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val imagesGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        val videoGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

        return cameraGranted && imagesGranted && videoGranted && audioGranted
    }

    /**
     * Call this to start camera in a coroutine, as per your original code.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun startCameraInCoroutine() {
        lifecycleScope.launch {
            try {
                cameraController.startCamera()
                schedulePeriodicCapture(5000L)
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Failed to start camera: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
                e.printStackTrace()
            }
        }
    }

    /**
     * Old single-permission launcher (for < T Android versions),
     * preserved if you want to handle older devices:
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                lifecycleScope.launch {
                    try {
                        cameraController.startCamera()
                        schedulePeriodicCapture(5000L)
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            "Failed to start camera: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                        e.printStackTrace()
                    }
                }
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
                finish() // exit if you want consistent behavior
            }
        }

    /**
     * Create a simple layout programmatically:
     * A ConstraintLayout with one PreviewView filling the screen.
     */
    private fun createUI(): ConstraintLayout {
        val constraintLayout = ConstraintLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Camera preview
        previewView = PreviewView(this).apply {
            id = View.generateViewId()
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            )
        }
        constraintLayout.addView(previewView)

        ConstraintSet().apply {
            clone(constraintLayout)
            connect(previewView.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(
                previewView.id,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START
            )
            connect(
                previewView.id,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END
            )
            connect(
                previewView.id,
                ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM
            )
            applyTo(constraintLayout)
        }

        return constraintLayout
    }

    /**
     * Suspends repeatedly, capturing a photo every [intervalMs] ms.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun schedulePeriodicCapture(intervalMs: Long) {
        while (lifecycleScope.isActive) {
            val bitmap = try {
                cameraController.takePhoto()
            } catch (e: Exception) {
                // If there's an error capturing
                Toast.makeText(
                    this@MainActivity,
                    "Unable to capture photo",
                    Toast.LENGTH_SHORT
                ).show()
                null
            }

            if (bitmap != null) {
                println("one")
                PhotoDescriptionSpeakService.describe(bitmap)
                println("two")
                Toast.makeText(
                    this@MainActivity,
                    "Captured photo (as Bitmap)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            delay(intervalMs)
        }
    }
}
