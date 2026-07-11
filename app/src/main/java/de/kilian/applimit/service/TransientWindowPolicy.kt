package de.kilian.applimit.service

/** Evidence-backed filtering of transient windows from otherwise launchable apps. */
object TransientWindowPolicy {
    fun isOverlay(packageName: String, className: String?): Boolean =
        packageName == SAMSUNG_WALLET_PACKAGE && className == TRANSIENT_WALLET_WINDOW_CLASS

    private const val SAMSUNG_WALLET_PACKAGE = "com.samsung.android.spay"
    private const val TRANSIENT_WALLET_WINDOW_CLASS = "android.widget.FrameLayout"
}
