package com.example.deaddrop

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.graphics.Color
import android.hardware.*
import android.location.*
import android.net.*
import android.os.*
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog // FIXED: Restored missing import
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {
    private val targetNumbers = mutableSetOf<String>()
    private val targetEmails = mutableSetOf<String>()
    private val armedFileUris = mutableListOf<Uri>()
    private var cloudEndpoint: String = ""
    private var isRecording = false
    private var isCancelWindowActive = false
    private var countdownTimer: CountDownTimer? = null

    private lateinit var sensorManager: SensorManager
    private var accelValue = 0f
    private var accelCurrent = SensorManager.GRAVITY_EARTH
    private var accelLast = SensorManager.GRAVITY_EARTH

    private val contactPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val data = res.data
            if (data?.clipData != null) {
                for (i in 0 until data.clipData!!.itemCount) extractContact(data.clipData!!.getItemAt(i).uri)
            } else data?.data?.let { extractContact(it) }
            saveState()
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            armedFileUris.clear()
            armedFileUris.addAll(uris)
            updateTargetDisplay()
        }
    }

    private fun extractContact(uri: Uri) {
        contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use {
            if (it.moveToFirst()) {
                val num = it.getString(0).replace("\\s".toRegex(), "")
                targetNumbers.add(num)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        loadState()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL)

        setupTriggerLogic()

        findViewById<ImageButton>(R.id.btnInfo).setOnClickListener { showInfoDialog() }
        findViewById<Button>(R.id.btnPayload).setOnClickListener { filePicker.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.btnContacts).setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI).apply {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            contactPicker.launch(intent)
        }
        findViewById<Button>(R.id.btnSeedContacts).setOnClickListener { seedApk() }
        findViewById<Button>(R.id.btnQR).setOnClickListener { showConfig() }
        findViewById<TextView>(R.id.tvTargetList).setOnLongClickListener { showRemoveTargetDialog(); true }

        requestPermissions(arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS
        ), 101)

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTriggerLogic() {
        val btn = findViewById<Button>(R.id.btnTrigger)
        btn.setOnTouchListener { _, event ->
            if (isCancelWindowActive) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { startDeadDrop(); true }
                MotionEvent.ACTION_UP -> { if (isRecording) initiateGracePeriod(); true }
                else -> false
            }
        }
    }

    private fun startDeadDrop() {
        if (targetNumbers.isEmpty() && targetEmails.isEmpty()) return
        isRecording = true
        vibrate(300)
        sendPanicSms("DEADDROP ALERT: Initializing Capture...")

        val serviceIntent = Intent(this, RecordingService::class.java).apply {
            putExtra("SHOW_PREVIEW", true)
        }
        startForegroundService(serviceIntent)

        findViewById<Button>(R.id.btnTrigger).apply {
            text = "CAPTURING EVIDENCE..."
            setBackgroundColor(Color.RED)
        }
    }

    private fun initiateGracePeriod() {
        isCancelWindowActive = true
        stopService(Intent(this, RecordingService::class.java))
        val stealthIntent = Intent(this, RecordingService::class.java).apply {
            putExtra("SHOW_PREVIEW", false)
        }
        startForegroundService(stealthIntent)

        val btn = findViewById<Button>(R.id.btnTrigger)
        val tvCount = findViewById<TextView>(R.id.tvCountdown)
        btn.text = "ABORT DEADDROP"
        btn.setBackgroundColor(Color.YELLOW)
        btn.setTextColor(Color.BLACK)
        tvCount.visibility = View.VISIBLE
        countdownTimer = object : CountDownTimer(5000, 100) {
            override fun onTick(ms: Long) { tvCount.text = String.format("%.1f", ms / 1000.0) }
            override fun onFinish() {
                tvCount.visibility = View.INVISIBLE
                isCancelWindowActive = false
                stopAndDeploy()
            }
        }.start()
        btn.setOnClickListener { cancelDeployment() }
    }

    private fun cancelDeployment() {
        countdownTimer?.cancel()
        isRecording = false
        isCancelWindowActive = false
        findViewById<TextView>(R.id.tvCountdown).visibility = View.INVISIBLE
        stopService(Intent(this, RecordingService::class.java))
        findViewById<Button>(R.id.btnTrigger).apply {
            text = "START DEADDROP"
            setBackgroundColor(Color.parseColor("#B71C1C"))
            setTextColor(Color.WHITE)
            setOnClickListener(null)
            setupTriggerLogic()
        }
    }

    private fun sendPanicSms(customMsg: String? = null) {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var mapLink = "Location Unavailable"
        try {
            val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            mapLink = "http://googleusercontent.com/maps.google.com/${loc?.latitude},${loc?.longitude}"
        } catch (e: Exception) {}

        val message = customMsg ?: "DEADDROP EMERGENCY! Location: $mapLink"
        val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        targetNumbers.forEach { num ->
            sms.sendTextMessage(num, null, message, null, null)
        }
    }

    private fun stopAndDeploy() {
        isRecording = false
        stopService(Intent(this, RecordingService::class.java))

        Handler(Looper.getMainLooper()).postDelayed({
            val audio = File(cacheDir, "drop_audio.mp4")
            val video = File(cacheDir, "drop_video.mp4")

            if (cloudEndpoint.isNotBlank()) {
                if (audio.exists()) uploadToCloud(audio)
                if (video.exists()) uploadToCloud(video)
            } else {
                if (video.exists()) dispatchAutomated(video)
            }

            armedFileUris.forEach { uri ->
                try {
                    val file = File(getPathFromUri(uri) ?: return@forEach)
                    if (cloudEndpoint.isNotBlank()) uploadToCloud(file)
                } catch (e: Exception) {}
            }
        }, 500)

        findViewById<Button>(R.id.btnTrigger).apply {
            text = "START DEADDROP"
            setBackgroundColor(Color.parseColor("#B71C1C"))
            setTextColor(Color.WHITE)
            setupTriggerLogic()
        }
    }

    private fun dispatchAutomated(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val defaultSmsPkg = Telephony.Sms.getDefaultSmsPackage(this)

        targetNumbers.forEach { num ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra("address", num)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (defaultSmsPkg != null) setPackage(defaultSmsPkg)
            }
            try { startActivity(intent) } catch (e: Exception) { Log.e("DD", "MMS Injection Failed") }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                shareIntent.`package` = "com.discord"
                startActivity(shareIntent)
            } catch (e: Exception) {
                try {
                    shareIntent.`package` = "com.whatsapp"
                    startActivity(shareIntent)
                } catch (ex: Exception) {
                    startActivity(Intent.createChooser(shareIntent, "Redundant Dispatch"))
                }
            }
        }, 3000)

        sendPanicSms("DEADDROP FINISHED: Sequential Dispatch Initiated.")
    }

    private fun getPathFromUri(uri: Uri): String? {
        val projection = arrayOf(android.provider.MediaStore.Images.Media.DATA)
        contentResolver.query(uri, projection, null, null, null)?.use {
            val index = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)
            if (it.moveToFirst()) return it.getString(index)
        }
        return uri.path
    }

    private fun uploadToCloud(file: File) {
        Thread {
            try {
                val conn = URL(cloudEndpoint).openConnection() as HttpURLConnection
                conn.apply {
                    doOutput = true
                    requestMethod = "POST"
                    connectTimeout = 15000
                    setRequestProperty("Content-Type", "application/octet-stream")
                    setRequestProperty("File-Name", file.name)
                }
                file.inputStream().use { it.copyTo(conn.outputStream) }
                if (conn.responseCode == 200) file.delete()
                else dispatchAutomated(file)
            } catch (e: Exception) { dispatchAutomated(file) }
        }.start()
    }

    private fun seedApk() {
        try {
            val appInfo = packageManager.getPackageInfo(packageName, 0).applicationInfo ?: return
            val srcFile = File(appInfo.sourceDir)
            val destFile = File(cacheDir, "System_Utility.apk")
            srcFile.inputStream().use { it.copyTo(destFile.outputStream()) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", destFile)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Seed System"))
        } catch (e: Exception) {}
    }

    private fun showConfig() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 20, 50, 20) }
        val urlIn = EditText(this).apply { hint = "Cloud URL (HTTPS)"; setText(cloudEndpoint) }
        val emailIn = EditText(this).apply { hint = "Emails (csv)"; setText(targetEmails.joinToString(",")) }
        layout.addView(urlIn); layout.addView(emailIn)
        AlertDialog.Builder(this).setTitle("SYSTEM CONFIG").setView(layout).setPositiveButton("SAVE") { _, _ ->
            cloudEndpoint = urlIn.text.toString()
            targetEmails.clear()
            emailIn.text.toString().split(",").forEach { if(it.isNotBlank()) targetEmails.add(it.trim()) }
            saveState()
        }.show()
    }

    private fun showRemoveTargetDialog() {
        val all = (targetNumbers + targetEmails).toTypedArray()
        AlertDialog.Builder(this).setItems(all) { _, i ->
            targetNumbers.remove(all[i]); targetEmails.remove(all[i])
            saveState()
        }.show()
    }

    private fun saveState() {
        getSharedPreferences("DD", MODE_PRIVATE).edit().apply {
            putStringSet("nums", targetNumbers); putStringSet("emails", targetEmails)
            putString("cloud", cloudEndpoint); apply()
        }
        updateTargetDisplay()
    }

    private fun loadState() {
        val p = getSharedPreferences("DD", MODE_PRIVATE)
        targetNumbers.addAll(p.getStringSet("nums", emptySet()) ?: emptySet())
        targetEmails.addAll(p.getStringSet("emails", emptySet()) ?: emptySet())
        cloudEndpoint = p.getString("cloud", "") ?: ""
        updateTargetDisplay()
    }

    private fun updateTargetDisplay() {
        val status = if (cloudEndpoint.isNotBlank()) "ONLINE" else "LOCAL ONLY"
        findViewById<TextView>(R.id.tvTargetList).text = "MODE: $status\nTargets: ${targetNumbers.size}\nArmed Files: ${armedFileUris.size}"
    }

    private fun showInfoDialog() {
        val tutorial = """
            PROTOCOL GUIDE
            
            1. EMERGENCY TRIGGER: 
               Hold 'START DEADDROP'. A camera preview appears. 
               Release to start 5s countdown. Once finished, data is sent.
            
            2. AUTOMATED DISPATCH:
               The app pre-loads the video into your system's messaging app.
               It will target your selected numbers automatically.
               
            3. DOUBLE REDUNDANCY:
               3 seconds after the first attempt, it will try to push 
               the video through Discord or WhatsApp if available.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("SYSTEM INSTRUCTIONS")
            .setMessage(tutorial)
            .setPositiveButton("UNDERSTOOD", null)
            .show()
    }

    override fun onSensorChanged(e: SensorEvent) {
        accelLast = accelCurrent
        accelCurrent = sqrt(e.values[0]*e.values[0] + e.values[1]*e.values[1] + e.values[2]*e.values[2])
        accelValue = accelValue * 0.9f + (accelCurrent - accelLast)
        if (accelValue > 15 && !isRecording && !isCancelWindowActive) startDeadDrop()
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    private fun vibrate(ms: Long) { (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(ms, 255)) }
}