package de.kilian.applimit

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import de.kilian.applimit.data.OnboardingPreferences
import de.kilian.applimit.service.AccessibilityStatus
import de.kilian.applimit.service.ForegroundAnchorService
import de.kilian.applimit.service.WatchdogScheduler
import de.kilian.applimit.ui.config.ConfigurationHost
import de.kilian.applimit.ui.debug.DetectionDebugScreen
import de.kilian.applimit.ui.theme.AppLimitTheme
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AccessibilityStatus.isEnabled(this)) {
            ForegroundAnchorService.start(this)
            WatchdogScheduler.schedulePeriodic(this)
        }
        enableEdgeToEdge()
        setContent {
            AppLimitTheme {
                AppLimitRoot()
            }
        }
    }
}

@Composable
private fun AppLimitRoot() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val preferences = remember(context) { OnboardingPreferences(context.applicationContext) }
    val manualState by produceState<OnboardingPreferences.State?>(
        initialValue = null,
        key1 = preferences,
    ) {
        preferences.state.collect { value = it }
    }
    var permissionState by remember(context) {
        mutableStateOf(PermissionState.read(context))
    }
    var auxiliaryDestination by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (currentCoroutineContext().isActive) {
                permissionState = PermissionState.read(context)
                delay(750)
            }
        }
    }

    val onboardingComplete = manualState?.let { manual ->
        manual.restrictedSettingsConfirmed &&
            permissionState.accessibilityEnabled &&
            permissionState.overlayGranted &&
            permissionState.notificationsGranted &&
            permissionState.batteryOptimizationIgnored &&
            manual.neverSleepingAppConfirmed
    } == true

    when (auxiliaryDestination) {
        AuxiliaryDestination.DETECTION_DEBUG.name -> DetectionDebugScreen(
            onBack = { auxiliaryDestination = null },
        )
        AuxiliaryDestination.ONBOARDING.name -> AppLimitOnboarding(
            onBackToMain = if (onboardingComplete) {
                { auxiliaryDestination = null }
            } else {
                null
            },
            onOpenDetectionDebug = {
                auxiliaryDestination = AuxiliaryDestination.DETECTION_DEBUG.name
            },
        )
        else -> when {
            manualState == null -> Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("AppLimit wird geladen …")
                }
            }
            !onboardingComplete -> AppLimitOnboarding(
                onOpenDetectionDebug = {
                    auxiliaryDestination = AuxiliaryDestination.DETECTION_DEBUG.name
                },
            )
            else -> ConfigurationHost(
                onOpenOnboarding = {
                    auxiliaryDestination = AuxiliaryDestination.ONBOARDING.name
                },
                onOpenDetectionDebug = {
                    auxiliaryDestination = AuxiliaryDestination.DETECTION_DEBUG.name
                },
            )
        }
    }
}

private enum class AuxiliaryDestination {
    ONBOARDING,
    DETECTION_DEBUG,
}

@Composable
private fun AppLimitOnboarding(
    onBackToMain: (() -> Unit)? = null,
    onOpenDetectionDebug: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val preferences = remember(context) { OnboardingPreferences(context.applicationContext) }
    val manualState by preferences.state.collectAsStateWithLifecycle(
        initialValue = OnboardingPreferences.State(),
    )
    val scope = rememberCoroutineScope()

    var permissionState by remember(context) {
        mutableStateOf(PermissionState.read(context))
    }

    LaunchedEffect(context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (currentCoroutineContext().isActive) {
                permissionState = PermissionState.read(context)
                delay(750)
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        permissionState = PermissionState.read(context)
    }

    val checks = listOf(
        manualState.restrictedSettingsConfirmed,
        permissionState.accessibilityEnabled,
        permissionState.overlayGranted,
        permissionState.notificationsGranted,
        permissionState.batteryOptimizationIgnored,
        manualState.neverSleepingAppConfirmed,
    )
    val completedCount = checks.count { it }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                if (onBackToMain != null) {
                    OutlinedButton(
                        onClick = onBackToMain,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Zurück zur Hauptansicht")
                    }
                    Spacer(Modifier.height(18.dp))
                }
                Text(
                    text = "AppLimit einrichten",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Sechs Schritte sorgen dafür, dass AppLimit auf deinem Galaxy zuverlässig arbeiten kann. Der Status wird automatisch aktualisiert, solange dieser Bildschirm geöffnet ist.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                SetupSummaryCard(
                    completedCount = completedCount,
                    totalCount = checks.size,
                )
            }

            item {
                OutlinedButton(
                    onClick = onOpenDetectionDebug,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("Detection-Debug öffnen")
                }
            }

            item {
                PermissionStepCard(
                    number = 1,
                    title = "Eingeschränkte Einstellungen zulassen",
                    ready = manualState.restrictedSettingsConfirmed,
                    details = "Wichtig bei Installation über Browser oder Datei-Manager:\n\n1. App-Info öffnen.\n2. Oben rechts auf das Drei-Punkte-Menü (⋮) tippen.\n3. „Eingeschränkte Einstellungen zulassen“ bestätigen.\n4. Zu AppLimit zurückkehren und den Schritt unten markieren.\n\nBei Installation über adb entfällt dieser Schritt: Android wendet die Sperre nur bei Browser-/Datei-Manager-Sideloads an; bei adb-Installationen erscheint das Menü gar nicht. Android bietet für den Schalter keinen auslesbaren Status. Die Bestätigung wird deshalb nur lokal gespeichert.",
                    primaryActionLabel = "App-Info öffnen",
                    onPrimaryAction = { context.openAppDetails() },
                    secondaryActionLabel = if (manualState.restrictedSettingsConfirmed) {
                        "Bestätigung zurücksetzen"
                    } else {
                        "Als erledigt markieren"
                    },
                    onSecondaryAction = {
                        scope.launch {
                            preferences.setRestrictedSettingsConfirmed(
                                !manualState.restrictedSettingsConfirmed,
                            )
                        }
                    },
                )
            }

            item {
                PermissionStepCard(
                    number = 2,
                    title = "Bedienungshilfe aktivieren",
                    ready = permissionState.accessibilityEnabled,
                    details = if (manualState.restrictedSettingsConfirmed) {
                        "Öffne die installierten Dienste und aktiviere „AppLimit Überwachung“. Die Systemfreigabe wird hier live geprüft; die Detection läuft anschließend automatisch im Hintergrund."
                    } else {
                        "Schließe zuerst Schritt 1 ab. Ohne „Eingeschränkte Einstellungen zulassen“ blockiert Android den Schalter für seitengeladene Apps."
                    },
                    primaryActionLabel = "Bedienungshilfen öffnen",
                    primaryActionEnabled = manualState.restrictedSettingsConfirmed ||
                        permissionState.accessibilityEnabled,
                    onPrimaryAction = { context.openAccessibilitySettings() },
                )
            }

            item {
                PermissionStepCard(
                    number = 3,
                    title = "Über anderen Apps anzeigen",
                    ready = permissionState.overlayGranted,
                    details = "Aktiviere „Zulassen“, damit AppLimit Countdown und Sperrbildschirm über der limitierten App anzeigen kann.",
                    primaryActionLabel = "Overlay-Freigabe öffnen",
                    onPrimaryAction = { context.openOverlaySettings() },
                )
            }

            item {
                PermissionStepCard(
                    number = 4,
                    title = "Benachrichtigungen erlauben",
                    ready = permissionState.notificationsGranted,
                    details = "Benachrichtigungen werden für Vorwarnungen, den dauerhaften Dienststatus und Watchdog-Hinweise benötigt.",
                    primaryActionLabel = if (permissionState.notificationsGranted) {
                        "Benachrichtigungs-Einstellungen öffnen"
                    } else {
                        "Berechtigung anfragen"
                    },
                    onPrimaryAction = {
                        if (permissionState.notificationsGranted) {
                            context.openNotificationSettings()
                        } else {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    secondaryActionLabel = if (permissionState.notificationsGranted) {
                        null
                    } else {
                        "Alternativ in Einstellungen öffnen"
                    },
                    onSecondaryAction = if (permissionState.notificationsGranted) {
                        null
                    } else {
                        { context.openNotificationSettings() }
                    },
                )
            }

            item {
                PermissionStepCard(
                    number = 5,
                    title = "Batterieoptimierung ausschalten",
                    ready = permissionState.batteryOptimizationIgnored,
                    details = "Bestätige die Systemabfrage, damit Android AppLimit nicht durch die normale Batterieoptimierung anhält. Der Status wird live geprüft.",
                    primaryActionLabel = if (permissionState.batteryOptimizationIgnored) {
                        "Batterie-Einstellungen öffnen"
                    } else {
                        "Ausnahme anfordern"
                    },
                    onPrimaryAction = {
                        if (permissionState.batteryOptimizationIgnored) {
                            context.openBatteryOptimizationList()
                        } else {
                            context.requestBatteryOptimizationExemption()
                        }
                    },
                )
            }

            item {
                PermissionStepCard(
                    number = 6,
                    title = "In One UI niemals schlafen lassen",
                    ready = manualState.neverSleepingAppConfirmed,
                    details = "Öffne die Akku-Einstellungen und gehe zu:\n\nGerätewartung → Akku → Hintergrundnutzungsgrenzen → Nie schlafende Apps (je nach One-UI-Text auch „Apps, die nie im Standby sind“).\n\nFüge AppLimit hinzu und stelle sicher, dass die Hintergrundnutzung nicht eingeschränkt ist. Android stellt dafür keinen verlässlichen Status-Check bereit. Falls der Watchdog nach einem One-UI-Update anschlägt, prüfe diese Einstellung und die Batterieoptimierung erneut.",
                    primaryActionLabel = "Samsung Akku-Einstellungen öffnen",
                    onPrimaryAction = { context.openSamsungBatterySettings() },
                    secondaryActionLabel = if (manualState.neverSleepingAppConfirmed) {
                        "Bestätigung zurücksetzen"
                    } else {
                        "AppLimit wurde hinzugefügt"
                    },
                    onSecondaryAction = {
                        scope.launch {
                            preferences.setNeverSleepingAppConfirmed(
                                !manualState.neverSleepingAppConfirmed,
                            )
                        }
                    },
                )
            }

            item {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Alle Angaben bleiben lokal auf dem Gerät. Ein roter Status bedeutet, dass der jeweilige Schritt noch offen ist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SetupSummaryCard(
    completedCount: Int,
    totalCount: Int,
) {
    val complete = completedCount == totalCount
    val containerColor = if (complete) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (complete) "Bereit für AppLimit" else "Einrichtung läuft",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "$completedCount von $totalCount Schritten grün",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(ready = complete)
            }
            LinearProgressIndicator(
                progress = { completedCount.toFloat() / totalCount.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun PermissionStepCard(
    number: Int,
    title: String,
    ready: Boolean,
    details: String,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    primaryActionEnabled: Boolean = true,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    StatusChip(ready = ready)
                }
            }

            Text(
                text = details,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onPrimaryAction,
                enabled = primaryActionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(primaryActionLabel)
            }

            if (secondaryActionLabel != null && onSecondaryAction != null) {
                OutlinedButton(
                    onClick = onSecondaryAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(secondaryActionLabel)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(ready: Boolean) {
    val background = if (ready) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val foreground = if (ready) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Row(
        modifier = Modifier
            .semantics {
                stateDescription = if (ready) "Grün, bereit" else "Rot, offen"
            }
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(foreground),
        )
        Text(
            text = if (ready) "Grün · bereit" else "Rot · offen",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
        )
    }
}

private data class PermissionState(
    val accessibilityEnabled: Boolean,
    val overlayGranted: Boolean,
    val notificationsGranted: Boolean,
    val batteryOptimizationIgnored: Boolean,
) {
    companion object {
        fun read(context: Context): PermissionState {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return PermissionState(
                accessibilityEnabled = AccessibilityStatus.isEnabled(context),
                overlayGranted = Settings.canDrawOverlays(context),
                notificationsGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
                batteryOptimizationIgnored = powerManager.isIgnoringBatteryOptimizations(
                    context.packageName,
                ),
            )
        }
    }
}

private fun Context.openAppDetails() {
    startFirstAvailable(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$packageName".toUri(),
        ),
    )
}

private fun Context.openAccessibilitySettings() {
    startFirstAvailable(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
}

private fun Context.openOverlaySettings() {
    startFirstAvailable(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri(),
        ),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    )
}

private fun Context.openNotificationSettings() {
    startFirstAvailable(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
            Settings.EXTRA_APP_PACKAGE,
            packageName,
        ),
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$packageName".toUri(),
        ),
    )
}

@SuppressLint("BatteryLife")
private fun Context.requestBatteryOptimizationExemption() {
    startFirstAvailable(
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:$packageName".toUri(),
        ),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )
}

private fun Context.openBatteryOptimizationList() {
    startFirstAvailable(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
}

private fun Context.openSamsungBatterySettings() {
    startFirstAvailable(
        Intent().setComponent(
            ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
        ),
        Intent().setComponent(
            ComponentName(
                "com.samsung.android.sm",
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
        ),
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
}

private fun Context.startFirstAvailable(vararg intents: Intent) {
    for (intent in intents) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next device-specific fallback.
        } catch (_: SecurityException) {
            // Try the next public settings fallback.
        }
    }

    Toast.makeText(
        this,
        "Die passende Systemeinstellung konnte nicht geöffnet werden.",
        Toast.LENGTH_LONG,
    ).show()
}
