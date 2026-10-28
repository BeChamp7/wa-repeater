package com.fury.repeater

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast

/** Setup screen: walks through the two permissions and launches WhatsApp. */
class MainActivity : Activity() {

    private lateinit var statusOverlay: TextView
    private lateinit var statusService: TextView
    private lateinit var btnOverlay: TextView
    private lateinit var btnService: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusOverlay = findViewById(R.id.status_overlay)
        statusService = findViewById(R.id.status_service)
        btnOverlay = findViewById(R.id.btn_overlay)
        btnService = findViewById(R.id.btn_service)

        btnOverlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        btnService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find “Repeater (WhatsApp)” and turn it on", Toast.LENGTH_LONG).show()
        }

        findViewById<TextView>(R.id.btn_app_info).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }

        findViewById<TextView>(R.id.btn_whatsapp).setOnClickListener { openWhatsApp() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        statusOverlay.text = if (overlayOk) "✓ Allowed"
        else "Needed so you can type in the floating panel."
        btnOverlay.alpha = if (overlayOk) 0.4f else 1f

        val serviceOk = isServiceEnabled()
        statusService.text = if (serviceOk) "✓ On — the bubble is showing"
        else "This is what types and taps Send inside WhatsApp."
        btnService.alpha = if (serviceOk) 0.4f else 1f

        // If overlay permission was granted after the service started, redraw the bubble.
        RepeaterService.instance?.refreshOverlayMode()
    }

    private fun isServiceEnabled(): Boolean {
        if (RepeaterService.instance != null) return true
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, RepeaterService::class.java)
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == me
        }
    }

    private fun openWhatsApp() {
        for (pkg in RepeaterService.WA_PACKAGES) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                startActivity(launch)
                return
            }
        }
        Toast.makeText(this, "WhatsApp isn't installed", Toast.LENGTH_SHORT).show()
    }
}
