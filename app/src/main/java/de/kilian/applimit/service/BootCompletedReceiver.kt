package de.kilian.applimit.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.kilian.applimit.domain.WatchdogPolicy

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val now = System.currentTimeMillis()
        ServiceHealthStore(context).markDisconnected(now)
        ForegroundAnchorService.start(context)
        WatchdogScheduler.schedulePeriodic(context)
        WatchdogScheduler.scheduleBootCheck(context)

        if (!AccessibilityStatus.isEnabled(context)) {
            RobustnessNotifier(context).also { it.createChannels() }
                .notifyWatchdogIssue(WatchdogPolicy.Issue.ACCESSIBILITY_DISABLED)
        }
    }
}
