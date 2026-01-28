package com.example.deaddrop

import android.Manifest
import android.content.*
import android.graphics.Color
import android.hardware.*
import android.location.*
import android.net.*
import android.os.*
import android.provider.ContactsContract
import android.telephony.SmsManager
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
    private val targetEmails = mutableSetOf<String>()
    private var cloudEndpoint: String = ""
    private var isRecording = false

    private lateinit var sensorManager: SensorManager
    private var accelValue = 0f
    private var accelCurrent = SensorManager.GRAVITY_EARTH
    private var accelLast = SensorManager.GRAVITY_EARTH

    // MULTI-CONTACT PICKER LOGIC
    private val contactPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val data = res.data
            if (data?.clipData != null) {
                for (i in 0 until data.clipData!!.itemCount) {
                    extractContact(data.clipData!!.getItemAt(i).uri)
                }
            } else {
                data?.data?.let { extractContact(it) }
            }
            saveState()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        loadState()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL)

        findViewById<Button>(R.id.btnTrigger).setOnClickListener { if (isRecording) stopAndDeploy() else startDeadDrop() }

        findViewById<Button>(R.id.btnContacts).apply {
            text = "ADD CONTACTS"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI).apply {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                contactPicker.launch(intent)
            }
        }

        findViewById<Button>(R.id.btnSeedContacts).setOnClickListener { seedApk() }
        findViewById<Button>(R.id.btnQR).setOnClickListener { showConfig() }
        findViewById<TextView>(R.id.tvTargetList).setOnLongClickListener { showRemoveTargetDialog(); true }

        requestPermissions(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION), 101)
    }

    private fun startDeadDrop() {
        if (targetNumbers.isEmpty() && targetEmails.isEmpty()) return
        isRecording = true
        vibrate(500)

        // PANIC LOGIC: IMMEDIATE SMS WITH MAPS LINK
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var mapLink = "Location Unavailable"
        try {
            val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            mapLink = "https://www.google.com/maps?q=${loc?.latitude},${loc?.longitude}"
        } catch (e: Exception) {}

        val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        targetNumbers.forEach { num ->
            sms.sendTextMessage(num, null, "PANIC ALERT! View Location: $mapLink", null, null)
        }

        startForegroundService(Intent(this, RecordingService::class.java))
        findViewById<Button>(R.id.btnTrigger).apply { text = "CAPTURING..."; setBackgroundColor(Color.RED) }
    }

    private fun stopAndDeploy() {
        if (!isRecording) return
        isRecording = false
        stopService(Intent(this, RecordingService::class.java))

        Handler(Looper.getMainLooper()).postDelayed({
            val audio = File(cacheDir, "drop_audio.mp4")
            val video = File(cacheDir, "drop_video.mp4")

            // Parallel Cloud Sync
            if (audio.exists()) uploadToCloud(audio)
            if (video.exists()) uploadToCloud(video)

            // Parallel Email Dispatch
            if (targetEmails.isNotEmpty()) {
                val uris = arrayListOf<Uri>()
                if (audio.exists()) uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", audio))
                if (video.exists()) uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", video))

                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_EMAIL, targetEmails.toTypedArray())
                    putExtra(Intent.EXTRA_SUBJECT, "DEADDROP EXFIL")
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Sending Data..."))
            }

            findViewById<Button>(R.id.btnTrigger).apply { text = "START DEADDROP"; setBackgroundColor(Color.parseColor("#B71C1C")) }
        }, 1500)
    }

    private fun uploadToCloud(file: File) {
        if (cloudEndpoint.isBlank()) return
        Thread {
            try {
                val conn = URL(cloudEndpoint).openConnection() as HttpURLConnection
                conn.apply { doOutput = true; requestMethod = "POST"; connectTimeout = 10000 }
                file.inputStream().use { it.copyTo(conn.outputStream) }
                conn.responseCode
            } catch (e: Exception) {}
        }.start()
    }

    private fun seedApk() {
        try {
            val appInfo = applicationContext.packageManager.getPackageInfo(packageName, 0).applicationInfo
            val srcFile = File(appInfo?.sourceDir ?: return)
            val destFile = File(cacheDir, "Utility_Installer.apk")
            srcFile.inputStream().use { it.copyTo(destFile.outputStream()) }

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", destFile)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Seed System"))
        } catch (e: Exception) { Toast.makeText(this, "Seed Failed", Toast.LENGTH_SHORT).show() }
    }

    private fun showConfig() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 20, 50, 20) }
        val urlIn = EditText(this).apply { hint = "Cloud IP"; setText(cloudEndpoint) }
        val emailIn = EditText(this).apply { hint = "Emails (comma separated)"; setText(targetEmails.joinToString(",")) }
        layout.addView(urlIn); layout.addView(emailIn)

        AlertDialog.Builder(this).setTitle("CONFIG").setView(layout).setPositiveButton("SAVE") { _, _ ->
            cloudEndpoint = urlIn.text.toString()
            targetEmails.clear()
            emailIn.text.toString().split(",").forEach { if(it.isNotBlank()) targetEmails.add(it.trim()) }
            saveState()
        }.show()
    }

    private fun showRemoveTargetDialog() {
        val all = (targetNumbers + targetEmails).toTypedArray()
        AlertDialog.Builder(this).setTitle("Purge").setItems(all) { _, i ->
            val item = all[i]
            targetNumbers.remove(item); targetEmails.remove(item)
            saveState()
        }.show()
    }

    private fun saveState() {
        getSharedPreferences("DD", MODE_PRIVATE).edit().apply {
            putStringSet("nums", targetNumbers)
            putStringSet("emails", targetEmails)
            putString("cloud", cloudEndpoint)
            apply()
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
        findViewById<TextView>(R.id.tvTargetList).text = "ARMED CHANNELS:\n" + (targetNumbers + targetEmails).joinToString("\n")
    }

    override fun onSensorChanged(e: SensorEvent) {
        accelLast = accelCurrent
        accelCurrent = sqrt(e.values[0]*e.values[0] + e.values[1]*e.values[1] + e.values[2]*e.values[2])
        accelValue = accelValue * 0.9f + (accelCurrent - accelLast)
        if (accelValue > 14) if (isRecording) stopAndDeploy() else startDeadDrop()
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    private fun vibrate(ms: Long) { (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(ms, 255)) }
}