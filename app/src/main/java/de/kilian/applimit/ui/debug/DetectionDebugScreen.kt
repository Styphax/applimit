package de.kilian.applimit.ui.debug

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kilian.applimit.data.usage.DailyCounterEntity
import de.kilian.applimit.data.usage.RawUsageEventEntity
import de.kilian.applimit.data.usage.UsageRepository
import de.kilian.applimit.data.usage.UsageSessionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun DetectionDebugScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { UsageRepository.get(context.applicationContext) }
    var today by remember { mutableStateOf(LocalDate.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            today = LocalDate.now()
        }
    }

    val dayFlow = remember(repository, today) { repository.observeDay(today) }
    val debugDay by dayFlow.collectAsStateWithLifecycle(
        initialValue = UsageRepository.DebugDay(),
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedButton(onClick = onBack) {
                    Text("Zurück zu AppLimit")
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "Detection-Debug",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Heute, $today · Room-Livedaten. Zähler werden spätestens alle 7 Sekunden aktualisiert.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                DebugSectionHeader(
                    title = "Tageszähler pro App",
                    count = debugDay.counters.size,
                )
            }

            if (debugDay.counters.isEmpty()) {
                item { EmptyDebugCard("Noch keine getrackte Nutzer-App geöffnet.") }
            } else {
                items(
                    items = debugDay.counters,
                    key = { "counter:${it.packageName}" },
                ) { counter ->
                    DailyCounterCard(
                        counter = counter,
                        appLabel = rememberAppLabel(counter.packageName),
                    )
                }
            }

            item {
                DebugSectionHeader(
                    title = "Berechnete Sessions",
                    count = debugDay.sessions.size,
                )
            }

            if (debugDay.sessions.isEmpty()) {
                item { EmptyDebugCard("Noch keine Session in Room.") }
            } else {
                items(
                    items = debugDay.sessions,
                    key = { "session:${it.id}" },
                ) { session ->
                    SessionCard(
                        session = session,
                        appLabel = rememberAppLabel(session.packageName),
                    )
                }
            }

            item {
                DebugSectionHeader(
                    title = "Roh-Events",
                    count = debugDay.rawEvents.size,
                )
            }

            if (debugDay.rawEvents.isEmpty()) {
                item {
                    EmptyDebugCard(
                        "Noch keine Events. Prüfe, ob „AppLimit Überwachung“ in den Bedienungshilfen aktiv ist.",
                    )
                }
            } else {
                items(
                    items = debugDay.rawEvents,
                    key = { "event:${it.id}" },
                ) { event ->
                    RawEventCard(event)
                }
            }
        }
    }
}

@Composable
private fun DebugSectionHeader(
    title: String,
    count: Int,
) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DailyCounterCard(
    counter: DailyCounterEntity,
    appLabel: String,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = appLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = counter.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${counter.openings} Öffnungen · ${formatDuration(counter.durationMillis)}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SessionCard(
    session: UsageSessionEntity,
    appLabel: String,
) {
    val status = when {
        session.isFinalized -> "beendet"
        session.inactiveSinceEpochMillis != null -> "60-s-Debounce"
        else -> "läuft"
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "$appLabel · $status",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${formatTime(session.startedAtEpochMillis)} → ${formatTime(session.endedAtEpochMillis)} · ${formatDuration(session.durationMillis)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = session.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun RawEventCard(event: RawUsageEventEntity) {
    val packageText = event.reportedPackage ?: "–"
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "${formatTime(event.timestampEpochMillis)} · ${event.eventType}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = event.decision,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = packageText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!event.className.isNullOrBlank()) {
                Text(
                    text = event.className,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyDebugCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun rememberAppLabel(packageName: String): String {
    val context = LocalContext.current
    return remember(context, packageName) { context.appLabel(packageName) }
}

private fun Context.appLabel(packageName: String): String = try {
    val info = packageManager.getApplicationInfo(
        packageName,
        PackageManager.ApplicationInfoFlags.of(0L),
    )
    packageManager.getApplicationLabel(info).toString()
} catch (_: PackageManager.NameNotFoundException) {
    packageName
}

private fun formatDuration(durationMillis: Long): String = String.format(
    Locale.GERMANY,
    "%.1f s",
    durationMillis / 1_000.0,
)

private fun formatTime(epochMillis: Long): String = TIME_FORMATTER.format(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
)

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
