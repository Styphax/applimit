package de.kilian.applimit.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager

object AccessibilityStatus {
    fun isEnabled(context: Context): Boolean {
        val expectedService = ComponentName(context, AppLimitAccessibilityService::class.java)
        val manager = context.getSystemService(AccessibilityManager::class.java)
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { enabledService ->
                val serviceInfo = enabledService.resolveInfo.serviceInfo
                ComponentName(serviceInfo.packageName, serviceInfo.name) == expectedService
            }
    }
}
