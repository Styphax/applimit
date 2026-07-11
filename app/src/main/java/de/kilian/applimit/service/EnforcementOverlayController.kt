package de.kilian.applimit.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owns the two SYSTEM_ALERT_WINDOW surfaces used by enforcement. */
class EnforcementOverlayController(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private var countdownView: TextView? = null
    private var blockView: View? = null
    private var frictionCountdownJob: Job? = null

    fun showMinuteCountdown(seconds: Int, label: String): Boolean {
        if (blockView != null || seconds !in 1..60) {
            dismissMinuteCountdown()
            return false
        }
        countdownView?.let { existing ->
            existing.text = "$label · ${seconds}s"
            return true
        }
        if (!Settings.canDrawOverlays(appContext)) return false

        val view = TextView(appContext).apply {
            text = "$label · ${seconds}s"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(Color.rgb(0, 79, 70), dp(18).toFloat())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val maximumObscuringOpacity = appContext
            .getSystemService(InputManager::class.java)
            .maximumObscuringOpacityForTouch
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(8)
            alpha = (maximumObscuringOpacity - 0.05f).coerceIn(0.35f, 0.75f)
        }
        return addViewSafely(view, params).also { added ->
            if (added) countdownView = view
        }
    }

    fun showFrictionBlock(
        title: String,
        reason: String,
        smallConfirmationLabel: String,
        largeConfirmationLabel: String,
        onConfirmSmall: () -> Unit,
        onConfirmLarge: () -> Unit,
        onAbort: () -> Unit,
    ): Boolean {
        dismissMinuteCountdown()
        dismissBlock()
        if (!Settings.canDrawOverlays(appContext)) return false

        val waitSeconds = FRICTION_WAIT_MILLIS / 1_000L
        val countdown = blockText(waitSeconds.toString(), 58f, Typeface.BOLD)
        val guidance = blockText(
            "Warte $waitSeconds Sekunden. Danach kannst du bewusst fortfahren.",
            16f,
            Typeface.NORMAL,
        )
        lateinit var allButtons: List<Button>
        fun confirmButton(label: String, onConfirm: () -> Unit) = Button(appContext).apply {
            text = label
            isEnabled = false
            setOnClickListener {
                if (!isEnabled) return@setOnClickListener
                allButtons.forEach { it.isEnabled = false }
                onConfirm()
            }
        }
        val smallButton = confirmButton(smallConfirmationLabel, onConfirmSmall)
        val largeButton = confirmButton(largeConfirmationLabel, onConfirmLarge)
        val abortButton = Button(appContext).apply {
            text = "Abbrechen und zum Home-Screen"
            setOnClickListener {
                allButtons.forEach { it.isEnabled = false }
                onAbort()
            }
        }
        allButtons = listOf(smallButton, largeButton, abortButton)
        val root = blockRoot(
            title = title,
            reason = reason,
            extraViews = listOf(
                spacer(24),
                countdown,
                guidance,
                spacer(20),
                smallButton,
                spacer(8),
                largeButton,
                spacer(8),
                abortButton,
            ),
        )
        val added = addViewSafely(root, fullScreenParams())
        if (!added) return false
        blockView = root

        val unlockAt = SystemClock.elapsedRealtime() + FRICTION_WAIT_MILLIS
        frictionCountdownJob = scope.launch {
            while (isActive && blockView === root) {
                val remainingMillis = unlockAt - SystemClock.elapsedRealtime()
                if (remainingMillis <= 0L) {
                    countdown.text = "Bereit"
                    guidance.text = "Wähle eine Freigabe oder brich ab."
                    smallButton.isEnabled = true
                    largeButton.isEnabled = true
                    break
                }
                countdown.text = ((remainingMillis + 999L) / 1_000L).toString()
                delay(250L)
            }
        }
        return true
    }

    fun showHardBlock(
        title: String,
        reason: String,
        nextRelease: String,
        onAbort: () -> Unit,
    ): Boolean {
        dismissMinuteCountdown()
        dismissBlock()
        if (!Settings.canDrawOverlays(appContext)) return false

        val homeButton = Button(appContext).apply {
            text = "Zum Home-Screen"
            setOnClickListener {
                isEnabled = false
                onAbort()
            }
        }
        val root = blockRoot(
            title = title,
            reason = reason,
            extraViews = listOf(
                spacer(28),
                blockText(nextRelease, 20f, Typeface.BOLD),
                spacer(28),
                homeButton,
            ),
        )
        return addViewSafely(root, fullScreenParams()).also { added ->
            if (added) blockView = root
        }
    }

    fun dismissMinuteCountdown() {
        countdownView?.let(::removeViewSafely)
        countdownView = null
    }

    fun dismissBlock() {
        frictionCountdownJob?.cancel()
        frictionCountdownJob = null
        blockView?.let(::removeViewSafely)
        blockView = null
    }

    fun dismissAll() {
        dismissMinuteCountdown()
        dismissBlock()
    }

    private fun blockRoot(
        title: String,
        reason: String,
        extraViews: List<View>,
    ): LinearLayout = LinearLayout(appContext).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(28), dp(44), dp(28), dp(44))
        setBackgroundColor(Color.rgb(16, 20, 18))
        isClickable = true
        isFocusable = true
        addView(blockText(title, 30f, Typeface.BOLD))
        addView(spacer(12))
        addView(blockText(reason, 18f, Typeface.NORMAL))
        extraViews.forEach(::addView)
    }

    private fun blockText(value: String, sizeSp: Float, style: Int): TextView =
        TextView(appContext).apply {
            text = value
            setTextColor(Color.WHITE)
            textSize = sizeSp
            typeface = Typeface.create(Typeface.DEFAULT, style)
            gravity = Gravity.CENTER
        }

    private fun spacer(heightDp: Int): Space = Space(appContext).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun fullScreenParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.FILL
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun addViewSafely(view: View, params: WindowManager.LayoutParams): Boolean = try {
        windowManager.addView(view, params)
        true
    } catch (_: RuntimeException) {
        false
    }

    private fun removeViewSafely(view: View) {
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: RuntimeException) {
            // The permission or display can disappear while the service is tearing down.
        }
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()

    private companion object {
        const val FRICTION_WAIT_MILLIS = 5_000L
    }
}
