package de.kilian.applimit.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kilian.applimit.data.config.ConfigurationSnapshot
import de.kilian.applimit.data.usage.DailyCounterEntity
import de.kilian.applimit.data.usage.DailyLimitGrantEntity
import de.kilian.applimit.data.usage.FrictionEventEntity
import de.kilian.applimit.data.usage.UsageRepository
import de.kilian.applimit.domain.DailyGrantRecord
import de.kilian.applimit.domain.DailyUsageRecord
import de.kilian.applimit.domain.DashboardAggregator
import de.kilian.applimit.domain.DashboardDaySummary
import de.kilian.applimit.domain.DashboardFrictionRecord
import de.kilian.applimit.domain.DashboardTargetKey
import de.kilian.applimit.domain.DashboardTargetSummary
import de.kilian.applimit.domain.DashboardTargetType
import de.kilian.applimit.domain.WeeklyDashboardSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay

private enum class DashboardSection(val label: String) {
    DAY("Tag"),
    WEEK("Woche"),
    FRICTION("Friction"),
}

@Composable
fun DashboardHost(
    configuration: ConfigurationSnapshot,
    knownAppLabels: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember(context) { UsageRepository.get(context.applicationContext) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    var selectedDateText by rememberSaveable { mutableStateOf(today.toString()) }
    val selectedDate = runCatching { LocalDate.parse(selectedDateText) }.getOrDefault(today)
        .coerceAtMost(today)
    var historyDays by rememberSaveable { mutableIntStateOf(90) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            today = LocalDate.now()
        }
    }

    val dayData by remember(repository, selectedDate) {
        repository.observeDashboardDay(selectedDate)
    }.collectAsStateWithLifecycle(initialValue = UsageRepository.DashboardDay())
    val weekStart = today.minusDays(6)
    val weekCounters by remember(repository, weekStart, today) {
        repository.observeCounters(weekStart, today)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val historyStart = today.minusDays(historyDays.toLong() - 1L)
    val frictionHistory by remember(repository, historyStart, today) {
        repository.observeFrictionHistory(historyStart, today)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val labels = remember(configuration, knownAppLabels) {
        knownAppLabels + configuration.apps.associate { it.packageName to it.label }
    }
    val daySummary = remember(selectedDate, configuration, dayData, labels) {
        DashboardAggregator.aggregateDay(
            date = selectedDate,
            configuration = configuration,
            usage = dayData.counters.map(DailyCounterEntity::toUsageRecord),
            grants = dayData.targetGrants.mapNotNull(DailyLimitGrantEntity::toGrantRecord),
            frictionEvents = dayData.frictionEvents.map(FrictionEventEntity::toDashboardRecord),
            trackedLabels = labels,
        )
    }

    DashboardScreen(
        daySummary = daySummary,
        configuration = configuration,
        weekCounters = weekCounters.map(DailyCounterEntity::toUsageRecord),
        frictionHistory = frictionHistory,
        knownAppLabels = labels,
        today = today,
        selectedDate = selectedDate,
        historyDays = historyDays,
        onSelectDate = { selectedDateText = it.toString() },
        onLoadOlderHistory = { historyDays += 90 },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(
    daySummary: DashboardDaySummary,
    configuration: ConfigurationSnapshot,
    weekCounters: List<DailyUsageRecord>,
    frictionHistory: List<FrictionEventEntity>,
    knownAppLabels: Map<String, String>,
    today: LocalDate,
    selectedDate: LocalDate,
    historyDays: Int,
    onSelectDate: (LocalDate) -> Unit,
    onLoadOlderHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sectionName by rememberSaveable { mutableStateOf(DashboardSection.DAY.name) }
    val section = DashboardSection.entries.firstOrNull { it.name == sectionName }
        ?: DashboardSection.DAY
    var selectedWeekTarget by rememberSaveable { mutableStateOf(TOTAL_TARGET) }
    val weekTargets = remember(configuration) { configuration.dashboardTargets() }
    LaunchedEffect(weekTargets, selectedWeekTarget) {
        if (selectedWeekTarget != TOTAL_TARGET &&
            weekTargets.none { it.first == selectedWeekTarget }
        ) {
            selectedWeekTarget = TOTAL_TARGET
        }
    }
    val targetKey = weekTargets.firstOrNull { it.first == selectedWeekTarget }?.second
    val weekSummary = remember(today, configuration, weekCounters, targetKey) {
        DashboardAggregator.aggregateWeek(today, configuration, weekCounters, targetKey)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                text = "Dashboard",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Nutzung, Öffnungen, Limits und Friction im Zeitverlauf.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DashboardSection.entries.forEach { item ->
                    FilterChip(
                        selected = item == section,
                        onClick = { sectionName = item.name },
                        label = { Text(item.label) },
                    )
                }
            }
        }

        when (section) {
            DashboardSection.DAY -> dayContent(
                summary = daySummary,
                today = today,
                selectedDate = selectedDate,
                onSelectDate = onSelectDate,
            )

            DashboardSection.WEEK -> weekContent(
                summary = weekSummary,
                targetOptions = listOf(TOTAL_TARGET to null) + weekTargets,
                configuration = configuration,
                selectedTarget = selectedWeekTarget,
                onSelectTarget = { selectedWeekTarget = it },
            )

            DashboardSection.FRICTION -> frictionContent(
                events = frictionHistory,
                configuration = configuration,
                knownAppLabels = knownAppLabels,
                historyDays = historyDays,
                onLoadOlder = onLoadOlderHistory,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.dayContent(
    summary: DashboardDaySummary,
    today: LocalDate,
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
) {
    item {
        DateSelector(
            date = selectedDate,
            today = today,
            onSelectDate = onSelectDate,
        )
        Text(
            text = summary.activePlanName?.let { "Aktiver Plan: $it" }
                ?: "Für diesen Wochentag ist kein Plan aktiv.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    val groups = summary.monitoredTargets.filter { it.target.type == DashboardTargetType.GROUP }
    val apps = summary.monitoredTargets.filter { it.target.type == DashboardTargetType.APP }
    if (groups.isNotEmpty()) {
        item { DashboardSectionHeader("Gruppen") }
        items(groups, key = { "group:${it.target.id}" }) { TargetDayCard(it) }
    }
    item { DashboardSectionHeader("Überwachte Apps") }
    if (apps.isEmpty()) {
        item { EmptyCard("Noch keine Apps überwacht.") }
    } else {
        items(apps, key = { "app:${it.target.id}" }) { TargetDayCard(it) }
    }
    item { UnmonitoredSection(summary) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.weekContent(
    summary: WeeklyDashboardSummary,
    targetOptions: List<Pair<String, DashboardTargetKey?>>,
    configuration: ConfigurationSnapshot,
    selectedTarget: String,
    onSelectTarget: (String) -> Unit,
) {
    item {
        Text(
            text = "Letzte 7 Tage",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(targetOptions, key = { it.first }) { (storageKey, key) ->
                FilterChip(
                    selected = storageKey == selectedTarget,
                    onClick = { onSelectTarget(storageKey) },
                    label = { Text(key?.displayLabel(configuration) ?: "Gesamt") },
                )
            }
        }
    }
    item {
        WeeklySummaryCard(summary)
    }
    item {
        BarChartCard(
            title = "Nutzungszeit pro Tag",
            summary = summary,
            values = summary.days.map { it.durationMillis.toFloat() },
            valueLabel = { formatDuration(it.toLong()) },
            color = MaterialTheme.colorScheme.primary,
        )
    }
    item {
        BarChartCard(
            title = "Öffnungen pro Tag",
            summary = summary,
            values = summary.days.map { it.openings.toFloat() },
            valueLabel = { it.toInt().toString() },
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.frictionContent(
    events: List<FrictionEventEntity>,
    configuration: ConfigurationSnapshot,
    knownAppLabels: Map<String, String>,
    historyDays: Int,
    onLoadOlder: () -> Unit,
) {
    item {
        Text(
            text = "Friction-Historie",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = "${events.size} Durchgänge in den letzten $historyDays Tagen · neueste zuerst",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (events.isEmpty()) {
        item { EmptyCard("In diesem Zeitraum wurden keine Friction-Durchgänge bestätigt.") }
    } else {
        items(events, key = FrictionEventEntity::id) { event ->
            FrictionEventCard(event, configuration, knownAppLabels)
        }
    }
    item {
        OutlinedButton(
            onClick = onLoadOlder,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Weitere 90 Tage laden")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSelector(
    date: LocalDate,
    today: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = { onSelectDate(date.minusDays(1)) }) { Text("‹") }
        OutlinedButton(
            onClick = { showPicker = true },
            modifier = Modifier.weight(1f),
        ) {
            Text(if (date == today) "Heute · ${formatDate(date)}" else formatDate(date))
        }
        OutlinedButton(
            onClick = { onSelectDate(date.plusDays(1)) },
            enabled = date < today,
        ) { Text("›") }
    }

    if (showPicker) {
        val todayMillis = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis <= todayMillis
            },
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            onSelectDate(
                                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                            )
                        }
                        showPicker = false
                    },
                ) { Text("Übernehmen") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Abbrechen") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun TargetDayCard(summary: DashboardTargetSummary) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (summary.limitHits > 0) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summary.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (summary.target.type == DashboardTargetType.GROUP) {
                        Text(
                            text = "Gruppe · ${summary.memberPackages.size} Apps",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = "${formatDuration(summary.durationMillis)} · ${summary.openings}×",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            MetricProgress(
                label = "Nutzungszeit",
                usedLabel = formatDuration(summary.durationMillis),
                used = summary.durationMillis.toFloat(),
                baseLimit = summary.minuteLimit,
                grant = summary.grantedMinutes,
                unit = "Min",
                limitScale = 60_000f,
            )
            MetricProgress(
                label = "Öffnungen",
                usedLabel = summary.openings.toString(),
                used = summary.openings.toFloat(),
                baseLimit = summary.openingLimit,
                grant = summary.grantedOpenings,
                unit = "Öffn.",
                limitScale = 1f,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Limit-Treffer: ${summary.limitHits}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Friction: ${summary.frictionPassages}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun MetricProgress(
    label: String,
    usedLabel: String,
    used: Float,
    baseLimit: Int?,
    grant: Int,
    unit: String,
    limitScale: Float,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = if (baseLimit == null) "$usedLabel · kein Limit" else {
                    "$usedLabel / ${baseLimit + grant} $unit"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (baseLimit != null) {
            val effective = (baseLimit + grant).coerceAtLeast(1) * limitScale
            LinearProgressIndicator(
                progress = { (used / effective).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(8.dp)),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            if (grant > 0) {
                Text(
                    text = "Basis $baseLimit $unit + $grant $unit Extension",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun UnmonitoredSection(summary: DashboardDaySummary) {
    var expanded by rememberSaveable(summary.date.toString()) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Nicht überwacht, aber getrackt (${summary.unmonitoredApps.size}) ${if (expanded) "▲" else "▼"}",
            )
        }
        if (expanded) {
            if (summary.unmonitoredApps.isEmpty()) {
                Text(
                    "Keine weiteren getrackten Apps an diesem Tag.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                summary.unmonitoredApps.forEach { app ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, fontWeight = FontWeight.SemiBold)
                                if (app.label != app.packageName) {
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text("${formatDuration(app.durationMillis)} · ${app.openings}×")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeeklySummaryCard(summary: WeeklyDashboardSummary) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Wochensumme", style = MaterialTheme.typography.labelLarge)
            Text(
                "${formatDuration(summary.totalDurationMillis)} · ${summary.totalOpenings} Öffnungen",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Zum Vortag: ${formatSignedDuration(summary.durationChangeFromPreviousDayMillis)} · " +
                    "${formatSigned(summary.openingsChangeFromPreviousDay)} Öffnungen",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun BarChartCard(
    title: String,
    summary: WeeklyDashboardSummary,
    values: List<Float>,
    valueLabel: (Float) -> String,
    color: Color,
) {
    val max = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(145.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                summary.days.zip(values).forEach { (day, value) ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Text(
                            valueLabel(value),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .width(20.dp),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            if (value > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight((value / max).coerceIn(0.04f, 1f))
                                        .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                        .background(color)
                                        .semantics {
                                            contentDescription =
                                                "${formatShortDate(day.date)}: ${valueLabel(value)}"
                                        },
                                )
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(formatWeekday(day.date), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun FrictionEventCard(
    event: FrictionEventEntity,
    configuration: ConfigurationSnapshot,
    knownAppLabels: Map<String, String>,
) {
    val appLabel = knownAppLabels[event.triggeringPackageName] ?: event.triggeringPackageName
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(appLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(formatTimestamp(event.timestampEpochMillis), style = MaterialTheme.typography.labelMedium)
            }
            Text(
                "${event.ruleType.ruleLabel()} · ${event.targetLabel(configuration)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                event.extensionLabel(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun DashboardSectionHeader(title: String) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ConfigurationSnapshot.dashboardTargets(): List<Pair<String, DashboardTargetKey>> =
    buildList {
        groups.filter { group -> apps.any { it.groupId == group.id } }
            .sortedBy { it.name.lowercase() }
            .forEach { group ->
                val key = DashboardTargetKey(DashboardTargetType.GROUP, group.id.toString())
                add(key.storageKey() to key)
            }
        apps.sortedBy { it.label.lowercase() }.forEach { app ->
            val key = DashboardTargetKey(DashboardTargetType.APP, app.packageName)
            add(key.storageKey() to key)
        }
    }

private fun DashboardTargetKey.storageKey(): String = "${type.name}:$id"
private fun DashboardTargetKey.displayLabel(configuration: ConfigurationSnapshot): String = when (type) {
    DashboardTargetType.APP -> configuration.apps.firstOrNull { it.packageName == id }?.label ?: id
    DashboardTargetType.GROUP -> id.toLongOrNull()?.let { groupId ->
        configuration.groups.firstOrNull { it.id == groupId }?.name
    } ?: "Gruppe $id"
}

private fun DailyCounterEntity.toUsageRecord() = DailyUsageRecord(
    date = LocalDate.parse(date),
    packageName = packageName,
    openings = openings,
    durationMillis = durationMillis,
)

private fun DailyLimitGrantEntity.toGrantRecord(): DailyGrantRecord? {
    val type = runCatching { DashboardTargetType.valueOf(targetType) }.getOrNull() ?: return null
    return DailyGrantRecord(
        date = LocalDate.parse(date),
        target = DashboardTargetKey(type, targetId),
        grantedOpenings = grantedOpenings,
        grantedMinutes = grantedMinutes,
    )
}

private fun FrictionEventEntity.toDashboardRecord() = DashboardFrictionRecord(
    date = LocalDate.parse(date),
    triggeringPackageName = triggeringPackageName,
    targetType = targetType,
    targetId = targetId,
)

private fun FrictionEventEntity.targetLabel(configuration: ConfigurationSnapshot): String = when (targetType) {
    "APP" -> configuration.apps.firstOrNull { it.packageName == targetId }?.label ?: targetId
    "GROUP" -> targetId.toLongOrNull()?.let { id ->
        configuration.groups.firstOrNull { it.id == id }?.name
    } ?: "Gruppe $targetId"
    else -> "mehrere Limits"
}

private fun FrictionEventEntity.extensionLabel(): String {
    val variant = when {
        grantedMinutes == 1 || grantedOpenings == 1 -> "SMALL"
        grantedMinutes == 5 || grantedOpenings == 3 -> "LARGE"
        else -> "EXTENSION"
    }
    val amounts = buildList {
        if (grantedMinutes > 0) add("+$grantedMinutes Min")
        if (grantedOpenings > 0) add("+$grantedOpenings Öffn.")
    }.joinToString(" · ")
    return "$variant · $amounts"
}

private fun String.ruleLabel(): String = when (this) {
    "OPENINGS" -> "Öffnungslimit"
    "MINUTES" -> "Minutenlimit"
    else -> "Mehrere Regeltypen"
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private fun formatSignedDuration(millis: Long): String =
    (if (millis >= 0L) "+" else "−") + formatDuration(kotlin.math.abs(millis))

private fun formatSigned(value: Int): String = if (value >= 0) "+$value" else "−${-value}"

private fun formatDate(date: LocalDate): String = date.format(
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.GERMANY),
)

private fun formatTimestamp(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
    .atZone(java.time.ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("dd.MM. · HH:mm", Locale.GERMANY))

private fun formatWeekday(date: LocalDate): String = date.format(
    DateTimeFormatter.ofPattern("EE", Locale.GERMANY),
)

private fun formatShortDate(date: LocalDate): String = date.format(
    DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY),
)

private const val TOTAL_TARGET = "TOTAL"
