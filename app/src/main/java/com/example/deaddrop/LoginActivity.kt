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

        if (savedReal == null || savedDuress == null) {
            isSetupMode = true
            titleTxt.text = "PROVISIONING: SET SECURE PIN"
        }

        btnLgn.setOnClickListener {
            val pin = pinIn.text.toString()
            if (pin.length < 4) {
                Toast.makeText(this, "MINIMUM 4 DIGITS", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isSetupMode) {
                val existingReal = prefs.getString("real_pin", null)
                if (existingReal == null) {
                    prefs.edit().putString("real_pin", pin).apply()
                    pinIn.text.clear()
                    titleTxt.text = "SET DURESS PIN"
                } else {
                    if (pin == existingReal) {
                        Toast.makeText(this, "PINS MUST NOT MATCH", Toast.LENGTH_LONG).show()
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
                        // RICKROLL PROTOCOL - Immediate diversion
                        val rickroll = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
                        startActivity(rickroll)
                        finish()
                    }
                    else -> {
                        // EXIT PROTOCOL - Closes the app entirely on wrong PIN
                        finishAffinity()
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
                val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                // Corrected string interpolation for functional Maps link
                val msg = "DURESS ALERT! Location: http://googleusercontent.com/maps.google.com/${loc?.latitude},${loc?.longitude}"
                val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                targets.forEach { sms.sendTextMessage(it, null, msg, null, null) }
            } catch (e: Exception) {}
        }.start()
    }
}