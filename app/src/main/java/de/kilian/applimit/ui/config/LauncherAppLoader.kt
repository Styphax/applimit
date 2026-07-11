package de.kilian.applimit.ui.config

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun loadLauncherApps(context: Context): List<LauncherAppUi> = withContext(Dispatchers.IO) {
    val packageManager = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val collator = Collator.getInstance(Locale.GERMANY)

    packageManager.queryIntentActivities(
        launcherIntent,
        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
    )
        .asSequence()
        .mapNotNull { resolveInfo ->
            val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == context.packageName) return@mapNotNull null

            LauncherAppUi(
                packageName = packageName,
                label = resolveInfo.loadLabel(packageManager)?.toString()?.trim()
                    .takeUnless { it.isNullOrBlank() }
                    ?: packageName,
                icon = runCatching { resolveInfo.loadIcon(packageManager) }.getOrNull(),
            )
        }
        .distinctBy(LauncherAppUi::packageName)
        .sortedWith { first, second ->
            val labelOrder = collator.compare(first.label, second.label)
            if (labelOrder != 0) labelOrder else first.packageName.compareTo(second.packageName)
        }
        .toList()
}
