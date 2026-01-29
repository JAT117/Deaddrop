package com.example.deaddrop

import android.content.*
import android.location.LocationManager
import android.net.Uri
import android.os.*
import android.telephony.SmsManager
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {
    private var isSetupMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val prefs = getSharedPreferences("DeadDropPrefs", Context.MODE_PRIVATE)
        val savedReal = prefs.getString("real_pin", null)
        val savedDuress = prefs.getString("duress_pin", null)

        val pinIn = findViewById<EditText>(R.id.etPin)
        val titleTxt = findViewById<TextView>(R.id.tvTitle)
        val btnLgn = findViewById<Button>(R.id.btnLogin)

        // Setup Mode Detection
        if (savedReal == null || savedDuress == null) {
            isSetupMode = true
            titleTxt.text = "SETUP: ENTER SECURE PIN (4+ Digits)"
        } else {
            titleTxt.text = "ENTER PIN"
        }

        btnLgn.setOnClickListener {
            val pin = pinIn.text.toString()

            // SECURITY CHECK: 4 Digits Minimum
            if (pin.length < 4) {
                Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isSetupMode) {
                val existingReal = prefs.getString("real_pin", null)
                if (existingReal == null) {
                    prefs.edit().putString("real_pin", pin).apply()
                    pinIn.text.clear()
                    titleTxt.text = "SETUP: ENTER DURESS PIN (4+ Digits)"
                } else {
                    if (pin == existingReal) {
                        Toast.makeText(this, "Duress PIN cannot match Real PIN", Toast.LENGTH_LONG).show()
                    } else {
                        prefs.edit().putString("duress_pin", pin).apply()
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                }
            } else {
                when (pin) {
                    savedReal -> {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                    savedDuress -> {
                        triggerSilentDuress()
                        Toast.makeText(this, "System Error 505", Toast.LENGTH_LONG).show() // Fake error
                        finishAffinity()
                    }
                    else -> {
                        Toast.makeText(this, "Invalid PIN", Toast.LENGTH_SHORT).show()
                        pinIn.text.clear()
                    }
                }
            }
        }
    }

    private fun triggerSilentDuress() {
        Thread {
            val prefs = getSharedPreferences("DD", Context.MODE_PRIVATE)
            val targets = prefs.getStringSet("nums", emptySet()) ?: emptySet()
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try {
                val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                // Fixed Link
                val mapLink = if (loc != null) "https://maps.google.com/?q=${loc.latitude},${loc.longitude}" else "Loc: Unknown"
                val msg = "DURESS ALERT! $mapLink"

                val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                targets.forEach { sms.sendTextMessage(it, null, msg, null, null) }
            } catch (e: Exception) {}
        }.start()
    }
}