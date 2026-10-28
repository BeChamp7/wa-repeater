package com.fury.repeater

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast

/** Setup screen: unlock restricted settings → overlay → accessibility (with disclosure) → use. */
class MainActivity : Activity() {

    private lateinit var statusRestricted: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var statusService: TextView
    private lateinit var btnAppInfo: TextView
    private lateinit var btnOverlay: TextView
    private lateinit var btnService: TextView
    private var restrictedHelp: CharSequence = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusRestricted = findViewById(R.id.status_restricted)
        statusOverlay = findViewById(R.id.status_overlay)
        statusService = findViewById(R.id.status_service)
        btnAppInfo = findViewById(R.id.btn_app_info)
        btnOverlay = findViewById(R.id.btn_overlay)
        btnService = findViewById(R.id.btn_service)
        restrictedHelp = statusRestricted.text

        findViewById<TextView>(R.id.text_version).text = "Version " + versionName()

        btnAppInfo.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
            Toast.makeText(this, "Tap ⋮ (top right) → Allow restricted settings", Toast.LENGTH_LONG).show()
        }

        btnOverlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        btnService.setOnClickListener { showAccessibilityDisclosure() }

        findViewById<TextView>(R.id.btn_whatsapp).setOnClickListener { openWhatsApp() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        val serviceOk = isServiceEnabled()

        // If either switch got turned on, restricted settings are clearly unlocked.
        if (overlayOk || serviceOk) {
            statusRestricted.text = "✓ Done"
            btnAppInfo.alpha = 0.4f
        } else {
            statusRestricted.text = restrictedHelp
            btnAppInfo.alpha = 1f
        }

        statusOverlay.text = if (overlayOk) "✓ Allowed"
        else "Lets the floating panel appear over WhatsApp so you can type in it."
        btnOverlay.alpha = if (overlayOk) 0.4f else 1f

        statusService.text = if (serviceOk) "✓ On — the bubble shows whenever WhatsApp is open"
        else "Types your message and taps Send inside WhatsApp."
        btnService.alpha = if (serviceOk) 0.4f else 1f

        // If overlay permission was granted after the service started, the next panel uses it.
        RepeaterService.instance?.refreshOverlayMode()
    }

    /** Prominent disclosure before sending the user to Accessibility settings. */
    private fun showAccessibilityDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("How Repeater uses Accessibility")
            .setMessage(
                "Repeater uses the Accessibility service only to:\n\n" +
                    "• show the floating bubble while WhatsApp is open\n" +
                    "• put your message into the open WhatsApp chat and tap Send, " +
                    "at the delay you choose, until you stop it\n\n" +
                    "It does not read, store or send your chats or anything else on your screen. " +
                    "It has no internet access, so nothing leaves your phone.\n\n" +
                    "On the next screen, tap “Repeater (WhatsApp)” and turn it on."
            )
            .setPositiveButton("Continue") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Not now", null)
            .show()
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

    private fun versionName(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
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
