package de.kilian.applimit.ui.config

import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.time.DayOfWeek
import java.util.Locale
import java.util.UUID

private enum class MainDestination(
    val label: String,
    val shortLabel: String,
) {
    DASHBOARD("Statistik", "S"),
    APPS("Apps", "A"),
    GROUPS("Gruppen", "G"),
    PLANS("Pläne", "P"),
    LIMITS("Limits", "L"),
    MORE("Mehr", "⋯"),
}

@Composable
fun AppLimitMainScreen(
    state: ConfigurationUiState,
    launcherApps: List<LauncherAppUi>,
    launcherAppsLoading: Boolean,
    launcherAppsError: String?,
    actions: ConfigurationUiActions,
    dashboardContent: @Composable (Modifier) -> Unit,
    onOpenOnboarding: () -> Unit,
    onOpenDetectionDebug: () -> Unit,
) {
    var destinationName by rememberSaveable { mutableStateOf(MainDestination.DASHBOARD.name) }
    val destination = MainDestination.entries.firstOrNull { it.name == destinationName }
        ?: MainDestination.DASHBOARD

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                modifier = Modifier.semantics {
                    contentDescription = "AppLimit Hauptnavigation"
                },
            ) {
                MainDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == destination,
                        onClick = { destinationName = item.name },
                        icon = {
                            Text(
                                text = item.shortLabel,
                                modifier = Modifier.clearAndSetSemantics { },
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        label = {
                            Text(
                                text = item.label,
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Navigation ${item.label}"
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (destination) {
            MainDestination.DASHBOARD -> dashboardContent(Modifier.padding(innerPadding))

            MainDestination.APPS -> AppSelectionScreen(
                state = state,
                launcherApps = launcherApps,
                loading = launcherAppsLoading,
                error = launcherAppsError,
                onReplaceApps = actions.replaceMonitoredApps,
                modifier = Modifier.padding(innerPadding),
            )

            MainDestination.GROUPS -> GroupsScreen(
                state = state,
                actions = actions,
                onOpenApps = { destinationName = MainDestination.APPS.name },
                modifier = Modifier.padding(innerPadding),
            )

            MainDestination.PLANS -> PlansScreen(
                plans = state.plans,
                onReplacePlans = actions.replacePlans,
                modifier = Modifier.padding(innerPadding),
            )

            MainDestination.LIMITS -> LimitsScreen(
                state = state,
                onSaveLimits = actions.saveTargetLimits,
                onOpenPlans = { destinationName = MainDestination.PLANS.name },
                onOpenApps = { destinationName = MainDestination.APPS.name },
                modifier = Modifier.padding(innerPadding),
            )

            MainDestination.MORE -> MoreScreen(
                onOpenOnboarding = onOpenOnboarding,
                onOpenDetectionDebug = onOpenDetectionDebug,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun AppSelectionScreen(
    state: ConfigurationUiState,
    launcherApps: List<LauncherAppUi>,
    loading: Boolean,
    error: String?,
    onReplaceApps: (List<MonitoredAppInputUi>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val monitoredByPackage = state.monitoredApps.associateBy(MonitoredAppUi::packageName)
    val allApps = remember(launcherApps, state.monitoredApps) {
        val installedPackages = launcherApps.mapTo(mutableSetOf(), LauncherAppUi::packageName)
        buildList {
            addAll(launcherApps)
            state.monitoredApps
                .filterNot { it.packageName in installedPackages }
                .forEach { app ->
                    add(
                        LauncherAppUi(
                            packageName = app.packageName,
                            label = app.label,
                            icon = null,
                        ),
                    )
                }
        }
    }
    val persistedSelection = state.monitoredApps.mapTo(mutableSetOf(), MonitoredAppUi::packageName)
    var localSelection by remember { mutableStateOf(persistedSelection.toSet()) }

    LaunchedEffect(persistedSelection) {
        localSelection = persistedSelection.toSet()
    }

    val normalizedQuery = query.trim()
    val filteredApps = remember(allApps, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            allApps
        } else {
            allApps.filter { app ->
                app.label.contains(normalizedQuery, ignoreCase = true) ||
                    app.packageName.contains(normalizedQuery, ignoreCase = true)
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Überwachte Apps",
                subtitle = "Wähle alle Apps aus, für die du Limits konfigurieren möchtest.",
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Apps durchsuchen") },
                placeholder = { Text("Name oder Paketname") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${localSelection.size} ausgewählt · ${allApps.size} Launcher-Apps",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (loading) {
            item { InfoCard("Launcher-Apps werden geladen …") }
        } else if (error != null) {
            item { ErrorCard(error) }
        } else if (filteredApps.isEmpty()) {
            item {
                InfoCard(
                    if (query.isBlank()) "Keine Launcher-Apps gefunden."
                    else "Keine App passt zu „$query“.",
                )
            }
        } else {
            items(
                items = filteredApps,
                key = LauncherAppUi::packageName,
            ) { app ->
                val selected = app.packageName in localSelection
                AppSelectionCard(
                    app = app,
                    selected = selected,
                    noLongerInstalled = app.packageName !in launcherApps.map(LauncherAppUi::packageName),
                    onSelectedChange = { checked ->
                        val nextSelection = if (checked) {
                            localSelection + app.packageName
                        } else {
                            localSelection - app.packageName
                        }
                        localSelection = nextSelection
                        onReplaceApps(
                            allApps
                                .filter { it.packageName in nextSelection }
                                .map { selectedApp ->
                                    MonitoredAppInputUi(
                                        packageName = selectedApp.packageName,
                                        label = monitoredByPackage[selectedApp.packageName]?.label
                                            ?: selectedApp.label,
                                    )
                                },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun AppSelectionCard(
    app: LauncherAppUi,
    selected: Boolean,
    noLongerInstalled: Boolean,
    onSelectedChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (noLongerInstalled) {
                    Text(
                        text = "Nicht mehr als Launcher-App gefunden",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Checkbox(
                checked = selected,
                onCheckedChange = onSelectedChange,
                modifier = Modifier.semantics {
                    contentDescription = "${app.label} überwachen"
                },
            )
        }
    }
}

@Composable
private fun AppIcon(app: LauncherAppUi) {
    if (app.icon == null) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .semantics { contentDescription = "Kein App-Symbol für ${app.label}" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.label.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
            },
            update = { imageView -> imageView.setImageDrawable(app.icon) },
            modifier = Modifier
                .size(46.dp)
                .semantics { contentDescription = "App-Symbol für ${app.label}" },
        )
    }
}

@Composable
private fun GroupsScreen(
    state: ConfigurationUiState,
    actions: ConfigurationUiActions,
    onOpenApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var createDialogOpen by rememberSaveable { mutableStateOf(false) }
    var renameGroup by remember { mutableStateOf<AppGroupUi?>(null) }
    var deleteGroup by remember { mutableStateOf<AppGroupUi?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                title = "Gruppen",
                subtitle = "Eine App kann höchstens einer Gruppe angehören. Beim Zuordnen zu einer neuen Gruppe wird sie aus der bisherigen gelöst.",
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { createDialogOpen = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Neue Gruppe anlegen")
            }
        }


        if (state.monitoredApps.isEmpty()) {
            item {
                ActionInfoCard(
                    message = "Wähle zuerst mindestens eine überwachte App aus.",
                    actionLabel = "Zur App-Auswahl",
                    onAction = onOpenApps,
                )
            }
        } else if (state.groups.isEmpty()) {
            item { InfoCard("Noch keine Gruppe angelegt.") }
        } else {
            items(
                items = state.groups,
                key = AppGroupUi::id,
            ) { group ->
                GroupCard(
                    group = group,
                    groups = state.groups,
                    apps = state.monitoredApps,
                    onRename = { renameGroup = group },
                    onDelete = { deleteGroup = group },
                    onAssignmentChange = actions.assignAppToGroup,
                )
            }
        }

        if (state.monitoredApps.any { it.groupId == null }) {
            item {
                val count = state.monitoredApps.count { it.groupId == null }
                Text(
                    text = "$count ${if (count == 1) "App ist" else "Apps sind"} keiner Gruppe zugeordnet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (createDialogOpen) {
        NameDialog(
            title = "Neue Gruppe",
            initialValue = "",
            confirmLabel = "Anlegen",
            onDismiss = { createDialogOpen = false },
            onConfirm = { name ->
                actions.createGroup(name)
                createDialogOpen = false
            },
        )
    }

    renameGroup?.let { group ->
        NameDialog(
            title = "Gruppe umbenennen",
            initialValue = group.name,
            confirmLabel = "Speichern",
            onDismiss = { renameGroup = null },
            onConfirm = { name ->
                actions.renameGroup(group.id, name)
                renameGroup = null
            },
        )
    }

    deleteGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { deleteGroup = null },
            title = { Text("„${group.name}“ löschen?") },
            text = {
                Text("Die Apps bleiben überwacht, werden aber aus der Gruppe gelöst. Limits dieser Gruppe werden entfernt.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.deleteGroup(group.id)
                        deleteGroup = null
                    },
                ) {
                    Text("Löschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteGroup = null }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun GroupCard(
    group: AppGroupUi,
    groups: List<AppGroupUi>,
    apps: List<MonitoredAppUi>,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAssignmentChange: (String, Long?) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val memberCount = apps.count { it.groupId == group.id }
                    Text(
                        text = "$memberCount ${if (memberCount == 1) "App" else "Apps"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRename) { Text("Umbenennen") }
                TextButton(onClick = onDelete) { Text("Löschen") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            apps.forEach { app ->
                val assignedHere = app.groupId == group.id
                val otherGroupName = app.groupId
                    ?.takeUnless { it == group.id }
                    ?.let { id -> groups.firstOrNull { it.id == id }?.name }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        if (otherGroupName != null) {
                            Text(
                                text = "Derzeit: $otherGroupName",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Checkbox(
                        checked = assignedHere,
                        onCheckedChange = { checked ->
                            onAssignmentChange(app.packageName, if (checked) group.id else null)
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "${app.label} der Gruppe ${group.name} zuordnen"
                        },
                    )
                }
            }
        }
    }
}

private data class EditablePlan(
    val localKey: String,
    val id: Long?,
    val name: String,
    val weekdays: Set<DayOfWeek>,
)

@Composable
private fun PlansScreen(
    plans: List<UsagePlanUi>,
    onReplacePlans: (List<PlanInputUi>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val persistedFingerprint = plans.joinToString("|") { plan ->
        "${plan.id}:${plan.name}:${plan.weekdays.sortedBy(DayOfWeek::getValue)}"
    }
    var drafts by remember { mutableStateOf(plans.toEditablePlans()) }
    var nameDialogPlan by remember { mutableStateOf<EditablePlan?>(null) }
    var creatingPlan by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(persistedFingerprint) {
        drafts = plans.toEditablePlans()
    }

    val dayOwners = DayOfWeek.entries.associateWith { day ->
        drafts.filter { day in it.weekdays }
    }
    val missingDays = dayOwners.filterValues(List<EditablePlan>::isEmpty).keys
    val duplicateDays = dayOwners.filterValues { it.size > 1 }.keys
    val namesValid = drafts.all { it.name.isNotBlank() } &&
        drafts.map { it.name.trim().lowercase(Locale.GERMANY) }.distinct().size == drafts.size
    val complete = drafts.isNotEmpty() && missingDays.isEmpty() && duplicateDays.isEmpty() && namesValid
    val draftFingerprint = drafts.joinToString("|") { plan ->
        "${plan.id}:${plan.name}:${plan.weekdays.sortedBy(DayOfWeek::getValue)}"
    }
    val hasChanges = draftFingerprint != persistedFingerprint

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                title = "Wochentagspläne",
                subtitle = "Jeder Wochentag muss genau einem Plan gehören. Eine neue Auswahl überträgt den Tag automatisch.",
            )
            Spacer(Modifier.height(16.dp))
            if (drafts.isEmpty()) {
                OutlinedButton(
                    onClick = {
                        drafts = listOf(
                            EditablePlan(
                                localKey = UUID.randomUUID().toString(),
                                id = null,
                                name = "Mo–Fr",
                                weekdays = DayOfWeek.entries.filter { it.value <= 5 }.toSet(),
                            ),
                            EditablePlan(
                                localKey = UUID.randomUUID().toString(),
                                id = null,
                                name = "Sa–So",
                                weekdays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Vorlage Mo–Fr / Sa–So verwenden")
                }
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = { creatingPlan = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Plan hinzufügen")
            }
        }


        item {
            PartitionStatusCard(
                missingDays = missingDays,
                duplicateDays = duplicateDays,
                namesValid = namesValid,
            )
        }

        items(
            items = drafts,
            key = EditablePlan::localKey,
        ) { plan ->
            PlanCard(
                plan = plan,
                onRename = { nameDialogPlan = plan },
                onDelete = { drafts = drafts.filterNot { it.localKey == plan.localKey } },
                onDayToggle = { day ->
                    drafts = drafts.map { candidate ->
                        when {
                            candidate.localKey == plan.localKey && day in candidate.weekdays -> {
                                candidate.copy(weekdays = candidate.weekdays - day)
                            }
                            candidate.localKey == plan.localKey -> {
                                candidate.copy(weekdays = candidate.weekdays + day)
                            }
                            else -> candidate.copy(weekdays = candidate.weekdays - day)
                        }
                    }
                },
                onAssignWeekdays = { assignedDays ->
                    drafts = drafts.map { candidate ->
                        if (candidate.localKey == plan.localKey) {
                            candidate.copy(weekdays = candidate.weekdays + assignedDays)
                        } else {
                            candidate.copy(weekdays = candidate.weekdays - assignedDays)
                        }
                    }
                },
            )
        }

        item {
            Button(
                onClick = {
                    onReplacePlans(
                        drafts.map { plan ->
                            PlanInputUi(
                                id = plan.id,
                                name = plan.name.trim(),
                                weekdays = plan.weekdays,
                            )
                        },
                    )
                },
                enabled = complete && hasChanges,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp),
            ) {
                Text(if (hasChanges) "Pläne speichern" else "Pläne gespeichert")
            }
            if (!complete) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Speichern ist erst möglich, wenn die Woche vollständig und ohne Überschneidung aufgeteilt ist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (creatingPlan) {
        NameDialog(
            title = "Plan hinzufügen",
            initialValue = "",
            confirmLabel = "Hinzufügen",
            onDismiss = { creatingPlan = false },
            onConfirm = { name ->
                drafts = drafts + EditablePlan(
                    localKey = UUID.randomUUID().toString(),
                    id = null,
                    name = name,
                    weekdays = emptySet(),
                )
                creatingPlan = false
            },
        )
    }

    nameDialogPlan?.let { plan ->
        NameDialog(
            title = "Plan umbenennen",
            initialValue = plan.name,
            confirmLabel = "Speichern",
            onDismiss = { nameDialogPlan = null },
            onConfirm = { name ->
                drafts = drafts.map { candidate ->
                    if (candidate.localKey == plan.localKey) candidate.copy(name = name)
                    else candidate
                }
                nameDialogPlan = null
            },
        )
    }
}

@Composable
private fun PartitionStatusCard(
    missingDays: Set<DayOfWeek>,
    duplicateDays: Set<DayOfWeek>,
    namesValid: Boolean,
) {
    val complete = missingDays.isEmpty() && duplicateDays.isEmpty() && namesValid
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (complete) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = if (complete) "Woche vollständig" else "Aufteilung unvollständig",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (missingDays.isNotEmpty()) {
                Text("Fehlend: ${missingDays.sortedBy(DayOfWeek::getValue).joinToString { it.shortGerman() }}")
            }
            if (duplicateDays.isNotEmpty()) {
                Text("Mehrfach vergeben: ${duplicateDays.sortedBy(DayOfWeek::getValue).joinToString { it.shortGerman() }}")
            }
            if (!namesValid) {
                Text("Plannamen müssen ausgefüllt und eindeutig sein.")
            }
            if (complete) {
                Text("Alle sieben Tage sind genau einmal vergeben.")
            }
        }
    }
}

@Composable
private fun PlanCard(
    plan: EditablePlan,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDayToggle: (DayOfWeek) -> Unit,
    onAssignWeekdays: (Set<DayOfWeek>) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = plan.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onRename) { Text("Umbenennen") }
                TextButton(onClick = onDelete) { Text("Löschen") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DayOfWeek.entries, key = DayOfWeek::name) { day ->
                    FilterChip(
                        selected = day in plan.weekdays,
                        onClick = { onDayToggle(day) },
                        label = { Text(day.shortGerman()) },
                        modifier = Modifier.semantics {
                            contentDescription = "${day.longGerman()} dem Plan ${plan.name} zuordnen"
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        onAssignWeekdays(DayOfWeek.entries.filter { it.value <= 5 }.toSet())
                    },
                ) {
                    Text("Mo–Fr zuordnen")
                }
                TextButton(
                    onClick = {
                        onAssignWeekdays(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
                    },
                ) {
                    Text("Sa–So zuordnen")
                }
            }
        }
    }
}

@Composable
private fun LimitsScreen(
    state: ConfigurationUiState,
    onSaveLimits: (Long, LimitTargetUi, TargetLimitsInputUi) -> Unit,
    onOpenPlans: () -> Unit,
    onOpenApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedPlanId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingTarget by remember { mutableStateOf<LimitTargetUi?>(null) }

    LaunchedEffect(state.plans) {
        if (state.plans.none { it.id == selectedPlanId }) {
            selectedPlanId = state.plans.firstOrNull()?.id
        }
    }

    val targets = buildList {
        state.groups.forEach { add(LimitTargetUi.Group(it.id)) }
        state.monitoredApps.forEach { add(LimitTargetUi.App(it.packageName)) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Limits",
                subtitle = "Konfiguriere Öffnungen, Minuten und eine optionale erlaubte Nutzungszeit pro Plan. AppLimit setzt die Regeln des aktiven Plans direkt durch.",
            )
        }


        when {
            state.plans.isEmpty() -> item {
                ActionInfoCard(
                    message = "Lege zuerst eine vollständige Wochentagsaufteilung an.",
                    actionLabel = "Zu den Plänen",
                    onAction = onOpenPlans,
                )
            }
            targets.isEmpty() -> item {
                ActionInfoCard(
                    message = "Wähle zuerst mindestens eine überwachte App aus.",
                    actionLabel = "Zur App-Auswahl",
                    onAction = onOpenApps,
                )
            }
            else -> {
                item {
                    Text(
                        text = "Plan auswählen",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.plans, key = UsagePlanUi::id) { plan ->
                            FilterChip(
                                selected = plan.id == selectedPlanId,
                                onClick = { selectedPlanId = plan.id },
                                label = { Text(plan.name) },
                            )
                        }
                    }
                }

                val planId = selectedPlanId
                if (planId != null) {
                    items(
                        items = targets,
                        key = LimitTargetUi::stableKey,
                    ) { target ->
                        val rules = state.limitRules.filter { rule ->
                            rule.planId == planId && rule.target == target
                        }
                        LimitTargetCard(
                            target = target,
                            state = state,
                            rules = rules,
                            onEdit = { editingTarget = target },
                        )
                    }
                }
            }
        }
    }

    val planId = selectedPlanId
    val target = editingTarget
    if (planId != null && target != null) {
        val rules = state.limitRules.filter { it.planId == planId && it.target == target }
        LimitEditorDialog(
            targetLabel = state.targetLabel(target),
            rules = rules,
            onDismiss = { editingTarget = null },
            onSave = { limits ->
                onSaveLimits(planId, target, limits)
                editingTarget = null
            },
        )
    }
}

@Composable
private fun LimitTargetCard(
    target: LimitTargetUi,
    state: ConfigurationUiState,
    rules: List<LimitRuleUi>,
    onEdit: () -> Unit,
) {
    val openings = rules.firstOrNull { it.type == LimitRuleTypeUi.OPENINGS }?.value
    val minutes = rules.firstOrNull { it.type == LimitRuleTypeUi.MINUTES }?.value
    val window = rules.firstOrNull { it.type == LimitRuleTypeUi.TIME_WINDOW }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = state.targetLabel(target),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when (target) {
                    is LimitTargetUi.App -> "App"
                    is LimitTargetUi.Group -> "Gruppe · gemeinsames Budget"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (openings == null && minutes == null && window == null) {
                Text(
                    text = "Noch kein Limit",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (openings != null) Text("$openings Öffnungen pro Tag")
                if (minutes != null) Text("$minutes Minuten pro Tag")
                if (window != null && window.endMinute != null) {
                    Text("Erlaubt ${formatMinute(window.value)}–${formatMinute(window.endMinute)}")
                }
            }
            OutlinedButton(
                onClick = onEdit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Limit bearbeiten")
            }
        }
    }
}

@Composable
private fun LimitEditorDialog(
    targetLabel: String,
    rules: List<LimitRuleUi>,
    onDismiss: () -> Unit,
    onSave: (TargetLimitsInputUi) -> Unit,
) {
    val openingRule = rules.firstOrNull { it.type == LimitRuleTypeUi.OPENINGS }
    val minuteRule = rules.firstOrNull { it.type == LimitRuleTypeUi.MINUTES }
    val windowRule = rules.firstOrNull { it.type == LimitRuleTypeUi.TIME_WINDOW }
    var openingsText by remember(targetLabel, rules) {
        mutableStateOf(openingRule?.value?.toString().orEmpty())
    }
    var minutesText by remember(targetLabel, rules) {
        mutableStateOf(minuteRule?.value?.toString().orEmpty())
    }
    var windowEnabled by remember(targetLabel, rules) { mutableStateOf(windowRule != null) }
    var startText by remember(targetLabel, rules) {
        mutableStateOf(formatMinute(windowRule?.value ?: 9 * 60))
    }
    var endText by remember(targetLabel, rules) {
        mutableStateOf(formatMinute(windowRule?.endMinute ?: 22 * 60))
    }

    val openings = openingsText.toIntOrNull()
    val minutes = minutesText.toIntOrNull()
    val startMinute = parseMinute(startText, allowEndOfDay = false)
    val endMinute = parseMinute(endText, allowEndOfDay = true)
    val openingsValid = openingsText.isBlank() || (openings != null && openings > 0)
    val minutesValid = minutesText.isBlank() || (minutes != null && minutes in 1..1_440)
    val windowValid = !windowEnabled || (
        startMinute != null && endMinute != null && startMinute < endMinute
        )
    val valid = openingsValid && minutesValid && windowValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(targetLabel) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Leere Felder bedeuten: kein Limit dieses Typs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = openingsText,
                    onValueChange = { openingsText = it.filter(Char::isDigit) },
                    label = { Text("Öffnungen pro Tag") },
                    singleLine = true,
                    isError = !openingsValid,
                    supportingText = if (!openingsValid) {
                        { Text("Mindestens 1 Öffnung") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { minutesText = it.filter(Char::isDigit) },
                    label = { Text("Minuten pro Tag") },
                    singleLine = true,
                    isError = !minutesValid,
                    supportingText = if (!minutesValid) {
                        { Text("1 bis 1.440 Minuten") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Erlaubte Nutzungszeit",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Außerhalb dieses Fensters gesperrt",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Checkbox(
                        checked = windowEnabled,
                        onCheckedChange = { windowEnabled = it },
                        modifier = Modifier.semantics {
                            contentDescription = "Zeitfenster aktivieren"
                        },
                    )
                }
                if (windowEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = startText,
                            onValueChange = { startText = it.take(5) },
                            label = { Text("Von") },
                            placeholder = { Text("09:00") },
                            singleLine = true,
                            isError = !windowValid,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = endText,
                            onValueChange = { endText = it.take(5) },
                            label = { Text("Bis") },
                            placeholder = { Text("22:00") },
                            singleLine = true,
                            isError = !windowValid,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (!windowValid) {
                        Text(
                            text = "Gültiges Fenster im Format HH:mm, z. B. 09:00–22:00.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        TargetLimitsInputUi(
                            openingsPerDay = openings,
                            minutesPerDay = minutes,
                            allowedTimeWindow = if (windowEnabled) {
                                AllowedTimeWindowUi(
                                    startMinute = requireNotNull(startMinute),
                                    endMinute = requireNotNull(endMinute),
                                )
                            } else {
                                null
                            },
                        ),
                    )
                },
                enabled = valid,
            ) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}

@Composable
private fun MoreScreen(
    onOpenOnboarding: () -> Unit,
    onOpenDetectionDebug: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                title = "Mehr",
                subtitle = "Berechtigungsstatus und Diagnose bleiben jederzeit erreichbar.",
            )
        }
        item {
            ActionInfoCard(
                message = "Prüfe alle sechs Systemfreigaben und One-UI-Einstellungen.",
                actionLabel = "Onboarding-Status öffnen",
                onAction = onOpenOnboarding,
            )
        }
        item {
            ActionInfoCard(
                message = "Sieh die unveränderten Erkennungsdaten, Sessions und Tageszähler live ein.",
                actionLabel = "Detection-Debug öffnen",
                onAction = onOpenDetectionDebug,
            )
        }
    }
}

@Composable
private fun ScreenHeader(
    title: String,
    subtitle: String,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InfoCard(message: String) {
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
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun ActionInfoCard(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(title, initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}

private fun List<UsagePlanUi>.toEditablePlans(): List<EditablePlan> = map { plan ->
    EditablePlan(
        localKey = "persisted:${plan.id}",
        id = plan.id,
        name = plan.name,
        weekdays = plan.weekdays,
    )
}

private fun ConfigurationUiState.targetLabel(target: LimitTargetUi): String = when (target) {
    is LimitTargetUi.App -> monitoredApps
        .firstOrNull { it.packageName == target.packageName }
        ?.label
        ?: target.packageName
    is LimitTargetUi.Group -> groups
        .firstOrNull { it.id == target.groupId }
        ?.name
        ?: "Gelöschte Gruppe"
}

private fun LimitTargetUi.stableKey(): String = when (this) {
    is LimitTargetUi.App -> "app:$packageName"
    is LimitTargetUi.Group -> "group:$groupId"
}

private fun DayOfWeek.shortGerman(): String = when (this) {
    DayOfWeek.MONDAY -> "Mo"
    DayOfWeek.TUESDAY -> "Di"
    DayOfWeek.WEDNESDAY -> "Mi"
    DayOfWeek.THURSDAY -> "Do"
    DayOfWeek.FRIDAY -> "Fr"
    DayOfWeek.SATURDAY -> "Sa"
    DayOfWeek.SUNDAY -> "So"
}

private fun DayOfWeek.longGerman(): String = when (this) {
    DayOfWeek.MONDAY -> "Montag"
    DayOfWeek.TUESDAY -> "Dienstag"
    DayOfWeek.WEDNESDAY -> "Mittwoch"
    DayOfWeek.THURSDAY -> "Donnerstag"
    DayOfWeek.FRIDAY -> "Freitag"
    DayOfWeek.SATURDAY -> "Samstag"
    DayOfWeek.SUNDAY -> "Sonntag"
}

private fun formatMinute(minute: Int): String {
    if (minute == 1_440) return "24:00"
    return String.format(Locale.GERMANY, "%02d:%02d", minute / 60, minute % 60)
}

private fun parseMinute(value: String, allowEndOfDay: Boolean): Int? {
    val parts = value.trim().split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (allowEndOfDay && hour == 24 && minute == 0) return 1_440
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}
