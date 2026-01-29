package com.example.deaddrop

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.hardware.camera2.*
import android.media.*
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import androidx.core.app.NotificationCompat
import java.io.File

class RecordingService : Service() {
    private var videoRecorder: MediaRecorder? = null
    private var cameraDevice: CameraDevice? = null
    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val channelId = "DeaddropCapture"
            val channel = NotificationChannel(channelId, "Service", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Recording Active")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            startForeground(1, notification)

            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            // Only attempt to add view if permission is granted
            if (Settings.canDrawOverlays(this)) {
                setupFloatingPreview(intent?.getBooleanExtra("SHOW_PREVIEW", false) ?: false)
            }
        } catch (e: Exception) {
            Log.e("DD", "Service Error: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY // Prevents crash loops
    }

    private fun setupFloatingPreview(visible: Boolean) {
        try {
            if (floatingView != null) windowManager.removeView(floatingView)

            val size = if (visible) 400 else 1
            // FIXED: Use TYPE_APPLICATION_OVERLAY for modern Android
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE

            val params = WindowManager.LayoutParams(
                size, size,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }

            floatingView = SurfaceView(this)
            windowManager.addView(floatingView, params)

            (floatingView as SurfaceView).holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { startVideoCapture(holder.surface) }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, height: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {}
            })
        } catch (e: Exception) {
            Log.e("DD", "Window Add Failed: ${e.message}")
        }
    }

    private fun startVideoCapture(previewSurface: Surface) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            manager.openCamera(manager.cameraIdList[0], object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    videoRecorder = MediaRecorder().apply {
                        setVideoSource(MediaRecorder.VideoSource.SURFACE)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                        setVideoEncodingBitRate(1000000)
                        setVideoFrameRate(30)
                        setVideoSize(640, 480)
                        setOutputFile(File(cacheDir, "drop_video.mp4").absolutePath)
                        prepare()
                    }
                    val recordSurface = videoRecorder?.surface ?: return
                    camera.createCaptureSession(listOf(previewSurface, recordSurface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            try {
                                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                builder.addTarget(previewSurface)
                                builder.addTarget(recordSurface)
                                session.setRepeatingRequest(builder.build(), null, null)
                                videoRecorder?.start()
                            } catch (e: Exception) { Log.e("DD", "Session Error") }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {}
                    }, null)
                }
                override fun onDisconnected(c: CameraDevice) { stopSelf() }
                override fun onError(c: CameraDevice, e: Int) { stopSelf() }
            }, null)
        } catch (e: Exception) { stopSelf() }
    }

    override fun onDestroy() {
        try {
            videoRecorder?.stop()
            videoRecorder?.release()
            cameraDevice?.close()
            if (floatingView != null) windowManager.removeView(floatingView)
        } catch (e: Exception) {}
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}