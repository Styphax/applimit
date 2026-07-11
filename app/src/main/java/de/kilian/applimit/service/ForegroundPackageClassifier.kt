package de.kilian.applimit.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager

/** Filters window events that do not represent a user-facing foreground app. */
class ForegroundPackageClassifier(
    context: Context,
) {
    enum class Action {
        TRACK,
        CLEAR_FOREGROUND,
        IGNORE_OVERLAY,
    }

    data class Decision(
        val action: Action,
        val packageName: String?,
        val reason: String,
    )

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val launchableCache = mutableMapOf<String, Boolean>()

    @Volatile
    private var launcherPackages: Set<String> = readLauncherPackages()

    @Volatile
    private var keyboardPackages: Set<String> = readKeyboardPackages()

    fun refreshDynamicPackages() {
        launcherPackages = readLauncherPackages()
        keyboardPackages = readKeyboardPackages()
        launchableCache.clear()
    }

    fun classify(reportedPackage: String?, className: String? = null): Decision {
        val packageName = reportedPackage?.trim().orEmpty()
        if (packageName.isEmpty()) {
            return Decision(Action.IGNORE_OVERLAY, null, "IGNORED_MISSING_PACKAGE")
        }

        if (packageName == appContext.packageName) {
            return Decision(Action.CLEAR_FOREGROUND, null, "CLEARED_APP_LIMIT")
        }

        if (packageName in launcherPackages) {
            return Decision(Action.CLEAR_FOREGROUND, null, "CLEARED_LAUNCHER")
        }

        if (packageName in keyboardPackages) {
            return Decision(Action.IGNORE_OVERLAY, null, "IGNORED_KEYBOARD")
        }

        if (TransientWindowPolicy.isOverlay(packageName, className)) {
            return Decision(Action.IGNORE_OVERLAY, null, "IGNORED_TRANSIENT_WALLET_WINDOW")
        }

        if (packageName in SYSTEM_OVERLAY_PACKAGES || packageName.contains("systemui")) {
            return Decision(Action.IGNORE_OVERLAY, null, "IGNORED_SYSTEM_UI")
        }

        if (!isLaunchable(packageName)) {
            return Decision(Action.IGNORE_OVERLAY, null, "IGNORED_NON_USER_APP")
        }

        return Decision(Action.TRACK, packageName, "TRACKED_USER_APP")
    }

    private fun isLaunchable(packageName: String): Boolean = launchableCache.getOrPut(packageName) {
        try {
            packageManager.getLaunchIntentForPackage(packageName) != null
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun readLauncherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager
            .queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    private fun readKeyboardPackages(): Set<String> {
        val inputMethodManager = appContext.getSystemService(InputMethodManager::class.java)
        return inputMethodManager.enabledInputMethodList
            .mapNotNull { inputMethod ->
                ComponentName.unflattenFromString(inputMethod.id)?.packageName
                    ?: inputMethod.packageName
            }
            .toSet()
    }

    private companion object {
        val SYSTEM_OVERLAY_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.samsung.android.permissioncontroller",
            "com.samsung.android.app.cocktailbarservice",
        )
    }
}
