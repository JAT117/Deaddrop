package com.example.deaddrop

import android.content.*
import android.location.LocationManager
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

        val pinInput = findViewById<EditText>(R.id.etPin)
        val titleText = findViewById<TextView>(R.id.tvTitle)
        val btnLogin = findViewById<Button>(R.id.btnLogin)

        if (savedReal == null || savedDuress == null) {
            isSetupMode = true
            titleText.text = "PROVISION SYSTEM: SET REAL PIN"
            btnLogin.text = "CONTINUE"
        }

        btnLogin.setOnClickListener {
            val input = pinInput.text.toString()
            if (input.length < 4) {
                Toast.makeText(this, "PIN must be 4+ digits", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isSetupMode) {
                if (prefs.getString("real_pin", null) == null) {
                    prefs.edit().putString("real_pin", input).apply()
                    pinInput.text.clear()
                    titleText.text = "SET DURESS PIN"
                } else {
                    if (input == prefs.getString("real_pin", "")) {
                        Toast.makeText(this, "Pins cannot match", Toast.LENGTH_SHORT).show()
                    } else {
                        prefs.edit().putString("duress_pin", input).apply()
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                }
            } else {
                when (input) {
                    savedReal -> { startActivity(Intent(this, MainActivity::class.java)); finish() }
                    savedDuress -> { triggerSilentDuress(); startActivity(Intent(this, DecoyActivity::class.java)); finish() }
                    else -> Toast.makeText(this, "DENIED", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun triggerSilentDuress() {
        Thread {
            val prefs = getSharedPreferences("DeadDropPrefs", Context.MODE_PRIVATE)
            val targets = prefs.getStringSet("targets", emptySet()) ?: emptySet()
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try {
                val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val msg = "DURESS ALERT: http://googleusercontent.com/maps.google.com/q=${loc?.latitude},${loc?.longitude}"
                val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                targets.forEach { sms.sendTextMessage(it, null, msg, null, null) }
            } catch (e: Exception) {}
        }.start()
    }
}