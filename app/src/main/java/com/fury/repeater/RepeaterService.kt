package com.fury.repeater

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Everything lives here:
 *  - a draggable bubble that only shows while WhatsApp is on screen
 *  - a small panel (text / emoji / delay / optional limit / Start)
 *  - the loop: put text in WhatsApp's message box → tap Send → wait → repeat
 *
 * Tap the bubble while it's red to stop. The loop also stops by itself if you
 * leave WhatsApp or the chat box / Send button can't be found 3 times in a row.
 */
class RepeaterService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RepeaterService? = null

        val WA_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

        private const val PREFS = "repeater"
        private const val MIN_INTERVAL = 1000L      // floor at 1s to keep WhatsApp from flagging you
        private const val MAX_INTERVAL = 30_000L
        private const val STEP = 500L
        private const val SEND_DELAY = 250L         // gap between filling the box and tapping Send
        private const val MAX_MISSES = 3
        private const val REOPEN_GUARD_MS = 400L

        private val EMOJIS = listOf(
            "❤️", "😂", "🔥", "😘", "🥺", "😭", "😡", "👋", "🙏", "💀", "👍", "🤣"
        )
        private val COLOR_IDLE = Color.parseColor("#FF00A884")
        private val COLOR_RUNNING = Color.parseColor("#FFE5484D")

        private fun isWa(pkg: CharSequence?): Boolean = pkg != null && pkg.toString() in WA_PACKAGES
    }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())     // bubble visibility checks
    private val loop = Handler(Looper.getMainLooper())   // the send loop only
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var lastPanelHide = 0L

    // Loop state
    private var running = false
    private var message = ""
    private var intervalMs = 2000L
    private var limit = 0
    private var sent = 0
    private var misses = 0

    // ───────────────────────── lifecycle ─────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        message = prefs.getString("message", "") ?: ""
        intervalMs = prefs.getLong("interval", 2000L).coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        limit = prefs.getInt("limit", 0)
        scheduleVisibilityCheck()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scheduleVisibilityCheck()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        running = false
        loop.removeCallbacksAndMessages(null)
        ui.removeCallbacksAndMessages(null)
        hidePanel()
        hideBubble()
        instance = null
    }

    /** Called from the setup screen; the panel picks its window type each time it opens. */
    fun refreshOverlayMode() {
        hidePanel()
    }

    // ───────────────────── show bubble only on WhatsApp ─────────────────────

    private val visibilityCheck = Runnable { updateVisibility() }

    private fun scheduleVisibilityCheck() {
        ui.removeCallbacks(visibilityCheck)
        ui.postDelayed(visibilityCheck, 200)
    }

    private fun updateVisibility() {
        if (whatsAppRoot() != null) {
            showBubble()
        } else {
            stopLoop("Stopped — you left WhatsApp")
            hidePanel()
            hideBubble()
        }
    }

    /** Root node of the WhatsApp window currently on screen, or null. Never reads other apps. */
    private fun whatsAppRoot(): AccessibilityNodeInfo? {
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = w.root ?: continue
                if (isWa(root.packageName)) return root
            }
        } catch (e: Exception) {
        }
        val active = rootInActiveWindow
        return if (active != null && isWa(active.packageName)) active else null
    }

    // ───────────────────────────── bubble ─────────────────────────────

    private fun showBubble() {
        if (bubble != null) return
        val size = dp(52)
        val tv = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            elevation = dp(6).toFloat()
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bx", resources.displayMetrics.widthPixels - size - dp(8))
            y = prefs.getInt("by", resources.displayMetrics.heightPixels / 3)
        }
        tv.setOnTouchListener(BubbleTouch())
        try {
            wm.addView(tv, params)
        } catch (e: Exception) {
            return
        }
        bubble = tv
        bubbleParams = params
        renderBubble()
    }

    private fun hideBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleParams = null
    }

    private fun renderBubble() {
        val b = bubble ?: return
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (running) COLOR_RUNNING else COLOR_IDLE)
        }
        if (running) {
            b.text = sent.toString()
            b.textSize = if (sent > 999) 13f else 16f
        } else {
            b.text = "↻"
            b.textSize = 22f
        }
    }

    private fun onBubbleTap() {
        if (running) {
            stopLoop("Stopped — sent $sent")
            return
        }
        if (panel != null) {
            hidePanel()
        } else if (SystemClock.uptimeMillis() - lastPanelHide > REOPEN_GUARD_MS) {
            // Guard: tapping the bubble also counts as an "outside" tap that just closed the panel.
            showPanel()
        }
    }

    private inner class BubbleTouch : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = bubbleParams ?: return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) moved = true
                    if (moved) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.edit().putInt("bx", p.x).putInt("by", p.y).apply()
                    } else {
                        v.performClick()
                        onBubbleTap()
                    }
                }
            }
            return true
        }
    }

    // ───────────────────────────── panel ─────────────────────────────

    private fun showPanel() {
        if (panel != null) return

        // With "Display over other apps" the panel can take keyboard input.
        // Without it we fall back to an accessibility overlay (emoji buttons still work).
        val canType = Settings.canDrawOverlays(this)
        val type = if (canType) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_panel, null)

        val params = WindowManager.LayoutParams(
            min(dp(340), resources.displayMetrics.widthPixels - dp(24)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }

        val input = v.findViewById<EditText>(R.id.input_message)
        val limitInput = v.findViewById<EditText>(R.id.input_limit)
        val intervalText = v.findViewById<TextView>(R.id.text_interval)

        input.setText(message)
        input.setSelection(input.text.length)
        if (limit > 0) limitInput.setText(limit.toString())
        if (!canType) input.hint = "Pick an emoji (allow overlay to type)"

        // Quick emojis: tap to insert at the cursor.
        val row = v.findViewById<LinearLayout>(R.id.emoji_row)
        for (emoji in EMOJIS) {
            val chip = TextView(ctx).apply {
                text = emoji
                textSize = 24f
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setOnClickListener {
                    val a = input.selectionStart
                    val b = input.selectionEnd
                    if (a < 0 || b < 0) {
                        input.append(emoji)
                    } else {
                        input.text.replace(min(a, b), max(a, b), emoji)
                    }
                }
            }
            row.addView(chip)
        }

        fun showInterval() {
            intervalText.text = String.format(Locale.US, "%.1fs", intervalMs / 1000.0)
        }
        showInterval()
        v.findViewById<View>(R.id.btn_minus).setOnClickListener {
            intervalMs = (intervalMs - STEP).coerceAtLeast(MIN_INTERVAL); showInterval()
        }
        v.findViewById<View>(R.id.btn_plus).setOnClickListener {
            intervalMs = (intervalMs + STEP).coerceAtMost(MAX_INTERVAL); showInterval()
        }

        fun saveDraft() {
            message = input.text.toString()
            limit = limitInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
            prefs.edit()
                .putString("message", message)
                .putLong("interval", intervalMs)
                .putInt("limit", limit)
                .apply()
        }

        v.findViewById<View>(R.id.btn_close).setOnClickListener {
            saveDraft()
            hidePanel()
        }

        v.findViewById<View>(R.id.btn_start).setOnClickListener {
            if (input.text.isBlank()) {
                toast("Type something or pick an emoji")
                return@setOnClickListener
            }
            saveDraft()
            hidePanel()
            startLoop()
        }

        // Tap anywhere outside the panel → close it (keeps the draft).
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                saveDraft()
                hidePanel()
                true
            } else {
                false
            }
        }

        try {
            wm.addView(v, params)
            panel = v
        } catch (e: Exception) {
            toast("Couldn't open the panel")
        }
    }

    private fun hidePanel() {
        val p = panel ?: return
        runCatching { wm.removeView(p) }
        panel = null
        lastPanelHide = SystemClock.uptimeMillis()
    }

    // ───────────────────────────── loop ─────────────────────────────

    private val tickRunnable = Runnable { tick() }

    private val pressSend = Runnable {
        if (!running) return@Runnable
        val root = whatsAppRoot()
        // Make sure our text actually landed, so we never tap the mic by mistake.
        // (An empty box reports its "Message" hint as text, so check isShowingHintText too.)
        val box = root?.let { findInput(it) }
        val typed = box != null && !box.isShowingHintText && !box.text.isNullOrEmpty()
        val send = root?.let { findSend(it) }
        if (!typed || send == null || !clickWithParents(send)) {
            miss("Stopped — couldn't find the Send button")
            return@Runnable
        }
        misses = 0
        sent++
        renderBubble()
        if (limit in 1..sent) {
            stopLoop("Done — sent $sent")
            return@Runnable
        }
        loop.postDelayed(tickRunnable, max(intervalMs - SEND_DELAY, 100L))
    }

    private fun startLoop() {
        running = true
        sent = 0
        misses = 0
        renderBubble()
        loop.removeCallbacksAndMessages(null)
        loop.postDelayed(tickRunnable, 600) // let WhatsApp take focus back first
    }

    private fun stopLoop(reason: String?) {
        if (!running) return
        running = false
        loop.removeCallbacksAndMessages(null)
        renderBubble()
        if (reason != null) toast(reason)
    }

    private fun tick() {
        if (!running) return
        val input = whatsAppRoot()?.let { findInput(it) }
        if (input == null) {
            miss("Stopped — open a chat first")
            return
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            miss("Stopped — couldn't type in the chat")
            return
        }
        loop.postDelayed(pressSend, SEND_DELAY)
    }

    private fun miss(reason: String) {
        misses++
        if (misses >= MAX_MISSES) stopLoop(reason)
        else loop.postDelayed(tickRunnable, intervalMs)
    }

    // ─────────────────────── finding WhatsApp's views ───────────────────────

    private fun findInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val pkg = root.packageName?.toString() ?: return null
        root.findAccessibilityNodeInfosByViewId("$pkg:id/entry")
            .firstOrNull { it.isVisibleToUser }?.let { return it }
        // Fallback if WhatsApp renames the view: first visible editable field.
        return findFirst(root) { it.isEditable && it.isVisibleToUser }
    }

    private fun findSend(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val pkg = root.packageName?.toString() ?: return null
        for (id in listOf("send", "send_container")) {
            root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
                .firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        // Fallback: a visible node described as "Send" (English UI).
        return findFirst(root) { n ->
            n.isVisibleToUser &&
                n.contentDescription?.toString()?.trim()?.equals("Send", ignoreCase = true) == true
        }
    }

    private fun findFirst(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        match: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        if (match(node)) return node
        for (i in 0 until node.childCount) {
            findFirst(node.getChild(i), depth + 1, match)?.let { return it }
        }
        return null
    }

    private fun clickWithParents(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 4) {
            if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
            hops++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ───────────────────────────── utils ─────────────────────────────

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
