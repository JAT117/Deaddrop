package com.example.deaddrop

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.graphics.Color
import android.hardware.*
import android.location.*
import android.media.ExifInterface
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {
    private val targetNumbers = mutableSetOf<String>()
    private val webhooks = mutableSetOf<String>()
    private val armedFileUris = mutableListOf<Uri>()
    private var isRecording = false
    private var isCancelWindowActive = false
    private var countdownTimer: CountDownTimer? = null

    private lateinit var sensorManager: SensorManager
    private var accelValue = 0f
    private var accelCurrent = SensorManager.GRAVITY_EARTH
    private var accelLast = SensorManager.GRAVITY_EARTH

    // --- ACTIVITY LAUNCHERS ---
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
            // Persist permissions immediately
            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) { Log.e("DD", "Perms failed: $uri") }
            }
            armedFileUris.addAll(uris)
            updateTargetDisplay()
        }
    }

    // --- LIFECYCLE ---
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "GRANT OVERLAY PERMISSION", Toast.LENGTH_LONG).show()
        }

        try {
            loadState()
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL)
        } catch (e: Exception) {}

        setupUI()

        requestPermissions(arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS
        ), 101)

        if (webhooks.isEmpty()) showInfoDialog()
    }

    private fun setupUI() {
        findViewById<Button>(R.id.btnQR).setOnClickListener { showConfig() }
        findViewById<ImageButton>(R.id.btnInfo).setOnClickListener { showInfoDialog() }
        findViewById<Button>(R.id.btnPayload).setOnClickListener { filePicker.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.btnContacts).setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI).apply {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            contactPicker.launch(intent)
        }
        findViewById<Button>(R.id.btnSeedContacts).setOnClickListener { seedApk() }

        findViewById<TextView>(R.id.tvTargetList).setOnLongClickListener {
            showRemoveTargetDialog()
            true
        }

        setupTriggerLogic()
    }

    // --- CORE LOGIC ---

    private fun startDeadDrop() {
        if (targetNumbers.isEmpty() && webhooks.isEmpty()) {
            Toast.makeText(this, "NO TARGETS SET!", Toast.LENGTH_SHORT).show()
            return
        }
        isRecording = true
        vibrate(300)

        sendSilentSms("DEADDROP TRIGGERED: Protocol Active.")

        val intent = Intent(this, RecordingService::class.java).apply { putExtra("SHOW_PREVIEW", true) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        findViewById<Button>(R.id.btnTrigger).apply {
            text = "CAPTURING..."
            setBackgroundColor(Color.RED)
        }
    }

    private fun stopAndDeploy() {
        isRecording = false
        stopService(Intent(this, RecordingService::class.java))

        Handler(Looper.getMainLooper()).postDelayed({
            val filesToPush = collectFiles()

            if (webhooks.isNotEmpty()) {
                filesToPush.forEach { file ->
                    webhooks.forEach { url -> uploadToApi(file, url) }
                }
            } else {
                // FALLBACK: SEQUENTIAL MMS (One file per draft to force contact lock)
                sendSilentSms("ALERT: Webhook missing. Dispatching Sequential MMS.")
                if (filesToPush.isNotEmpty()) dispatchSequentialMms(filesToPush)
            }
        }, 2000)

        resetUI()
    }

    private fun collectFiles(): List<File> {
        val files = mutableListOf<File>()
        val vid = File(cacheDir, "drop_video.mp4")
        if (vid.exists()) { embedGps(vid); files.add(vid) }

        armedFileUris.forEach { uri ->
            val safeFile = resolveToFile(uri)
            if (safeFile != null) files.add(safeFile)
        }
        return files
    }

    private fun resolveToFile(uri: Uri): File? {
        // ROBUST COPY: Ensures file is fully written to cache so MMS can read it
        return try {
            val fileName = getFileName(uri)
            val tempFile = File(cacheDir, fileName)

            // Overwrite if exists to ensure fresh copy
            if (tempFile.exists()) tempFile.delete()

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                    output.flush() // FORCE WRITE
                }
            }
            if (tempFile.length() > 0) tempFile else null
        } catch (e: Exception) { null }
    }

    private fun getFileName(uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx != -1) name = it.getString(idx)
            }
        }
        // Ensure extension exists
        if (!name.contains(".")) name += ".jpg"
        return name
    }

    // --- COMMUNICATIONS ---

    private fun sendSilentSms(msg: String) {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            val mapLink = if (loc != null) "http://googleusercontent.com/maps.google.com/?q=${loc.latitude},${loc.longitude}" else "Loc: Unknown"
            val finalMsg = "$msg\n$mapLink"

            val smsManager = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            val freshPrefs = getSharedPreferences("DD", MODE_PRIVATE)
            val targets = freshPrefs.getStringSet("nums", emptySet()) ?: emptySet()

            targets.forEach { num ->
                smsManager.sendTextMessage(num, null, finalMsg, null, null)
            }
        } catch (e: Exception) { Log.e("DD", "Silent SMS Failed") }
    }

    private fun dispatchSequentialMms(files: List<File>) {
        if (targetNumbers.isEmpty()) return

        val defaultSmsPkg = Telephony.Sms.getDefaultSmsPackage(this)
        val combinedNumbers = targetNumbers.joinToString(";") // Samsung/Pixel separator

        // LOOP: Send each file as its own Intent. This is the ONLY way to force the address.
        files.forEachIndexed { index, file ->
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

                    val intent = Intent(Intent.ACTION_SEND).apply {
                        // Use generic MIME to force MMS handler
                        type = "image/*"
                        if (file.name.endsWith(".mp4")) type = "video/mp4"

                        putExtra("address", combinedNumbers)
                        putExtra(Intent.EXTRA_PHONE_NUMBER, combinedNumbers) // Critical for some OEMs
                        putExtra(Intent.EXTRA_STREAM, uri)

                        // CRITICAL: ClipData grants permission to the receiving app
                        clipData = ClipData.newRawUri("Payload", uri)

                        if (defaultSmsPkg != null) setPackage(defaultSmsPkg)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to draft file: ${file.name}", Toast.LENGTH_SHORT).show()
                }
            }, index * 2000L) // 2 second delay between drafts to prevent app choking
        }
    }

    private fun uploadToApi(file: File, urlString: String) {
        Thread {
            try {
                val conn = URL(urlString).openConnection() as HttpURLConnection
                conn.apply {
                    doOutput = true
                    requestMethod = "POST"
                    connectTimeout = 30000
                    setRequestProperty("Content-Type", "application/octet-stream")
                    setRequestProperty("File-Name", file.name)
                }
                file.inputStream().use { it.copyTo(conn.outputStream) }
                if (conn.responseCode in 200..299) {
                    if (file.name == "drop_video.mp4") file.delete()
                }
            } catch (e: Exception) { Log.e("DD", "Upload Fail") }
        }.start()
    }

    // --- APK SEEDING ---
    private fun seedApk() {
        Thread {
            try {
                val appInfo = applicationInfo
                val originalFile = File(appInfo.sourceDir)
                val shareFile = File(externalCacheDir ?: cacheDir, "Deaddrop_Installer.apk")

                if (shareFile.exists()) shareFile.delete()

                originalFile.inputStream().use { input ->
                    shareFile.outputStream().use { output -> input.copyTo(output) }
                }

                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", shareFile)

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("APK", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(Intent.createChooser(intent, "Seed Application"))
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Seed Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    // --- EXIF GPS ---
    private fun embedGps(file: File) {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: return
            val exif = ExifInterface(file.absolutePath)

            val latRef = if (loc.latitude > 0) "N" else "S"
            val lonRef = if (loc.longitude > 0) "E" else "W"

            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, formatDMS(loc.latitude))
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, latRef)
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, formatDMS(loc.longitude))
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, lonRef)
            exif.saveAttributes()
        } catch (e: Exception) {}
    }

    private fun formatDMS(coordinate: Double): String {
        val absCoord = Math.abs(coordinate)
        val degrees = absCoord.toInt()
        val minutes = ((absCoord - degrees) * 60).toInt()
        val seconds = ((((absCoord - degrees) * 60) - minutes) * 60 * 1000).toInt()
        return "$degrees/1,$minutes/1,$seconds/1000"
    }

    private fun extractContact(uri: Uri) {
        contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use {
            if (it.moveToFirst()) targetNumbers.add(it.getString(0).replace("\\s".toRegex(), ""))
        }
    }

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

    private fun initiateGracePeriod() {
        isCancelWindowActive = true
        stopService(Intent(this, RecordingService::class.java))

        val btn = findViewById<Button>(R.id.btnTrigger)
        val tvCount = findViewById<TextView>(R.id.tvCountdown)
        btn.text = "ABORT"; btn.setBackgroundColor(Color.YELLOW); btn.setTextColor(Color.BLACK)
        tvCount.visibility = View.VISIBLE

        countdownTimer = object : CountDownTimer(5000, 100) {
            override fun onTick(ms: Long) { tvCount.text = String.format("%.1f", ms / 1000.0) }
            override fun onFinish() {
                tvCount.visibility = View.INVISIBLE
                isCancelWindowActive = false
                stopAndDeploy()
            }
        }.start()
        btn.setOnClickListener {
            countdownTimer?.cancel()
            resetUI()
        }
    }

    private fun resetUI() {
        isRecording = false
        isCancelWindowActive = false
        stopService(Intent(this, RecordingService::class.java))
        findViewById<TextView>(R.id.tvCountdown).visibility = View.INVISIBLE
        findViewById<Button>(R.id.btnTrigger).apply {
            text = "START DEADDROP"; setBackgroundColor(Color.parseColor("#B71C1C")); setTextColor(Color.WHITE)
        }
    }

    private fun showConfig() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 20, 50, 20) }
        val webIn = EditText(this).apply { hint = "Webhook URLs"; setText(webhooks.joinToString(",")) }
        val numIn = EditText(this).apply { hint = "Phone Numbers"; setText(targetNumbers.joinToString(",")) }
        layout.addView(webIn); layout.addView(numIn)
        AlertDialog.Builder(this).setTitle("CONFIG").setView(layout).setPositiveButton("SAVE") { _, _ ->
            webhooks.clear(); webIn.text.toString().split(",").forEach { if(it.isNotBlank()) webhooks.add(it.trim()) }
            targetNumbers.clear(); numIn.text.toString().split(",").forEach { if(it.isNotBlank()) targetNumbers.add(it.trim()) }
            saveState()
        }.show()
    }

    private fun showInfoDialog() {
        AlertDialog.Builder(this).setTitle("SETUP").setMessage("1. Webhooks: Add Discord URL for ZERO-TOUCH upload.\n2. SMS: GPS is automatic. FILES require 1-click per file (Sequential Mode).").setPositiveButton("OK", null).show()
    }

    private fun showRemoveTargetDialog() {
        val all = (targetNumbers + webhooks).toTypedArray()
        if (all.isEmpty()) return
        AlertDialog.Builder(this).setItems(all) { _, i ->
            targetNumbers.remove(all[i])
            webhooks.remove(all[i])
            saveState()
        }.show()
    }

    private fun saveState() {
        getSharedPreferences("DD", MODE_PRIVATE).edit().apply {
            putStringSet("nums", targetNumbers); putStringSet("webs", webhooks); apply()
        }
        updateTargetDisplay()
    }

    private fun loadState() {
        val p = getSharedPreferences("DD", MODE_PRIVATE)
        targetNumbers.addAll(p.getStringSet("nums", emptySet()) ?: emptySet())
        webhooks.addAll(p.getStringSet("webs", emptySet()) ?: emptySet())
        updateTargetDisplay()
    }

    private fun updateTargetDisplay() {
        val status = if (webhooks.isNotEmpty()) "ACTIVE: ${webhooks.size} HOOKS" else "LOCAL ONLY"
        findViewById<TextView>(R.id.tvTargetList).text = "MODE: $status\nTargets: ${targetNumbers.size}\nArmed Files: ${armedFileUris.size}"
    }

    override fun onSensorChanged(e: SensorEvent) {
        accelLast = accelCurrent
        accelCurrent = sqrt(e.values[0]*e.values[0] + e.values[1]*e.values[1] + e.values[2]*e.values[2])
        accelValue = accelValue * 0.9f + (accelCurrent - accelLast)
        if (accelValue > 15 && !isRecording) startDeadDrop()
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    private fun vibrate(ms: Long) { (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(ms, 255)) }
}