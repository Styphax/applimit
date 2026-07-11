package de.kilian.applimit.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import de.kilian.applimit.data.config.ConfigRepository
import de.kilian.applimit.data.config.ConfigurationSnapshot
import de.kilian.applimit.data.config.LimitRuleType
import de.kilian.applimit.data.config.LimitTarget
import de.kilian.applimit.data.usage.RawUsageEventEntity
import de.kilian.applimit.data.usage.UsageRepository
import de.kilian.applimit.domain.DeviceStateSnapshot
import de.kilian.applimit.domain.DailyResetCoordinator
import de.kilian.applimit.domain.EnforcementEvaluator
import de.kilian.applimit.domain.ScreenAvailabilityPolicy
import de.kilian.applimit.domain.ScreenSignal
import de.kilian.applimit.domain.UsageEngine
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Foreground detection, persistence and enforcement, serialized through one command queue. */
class AppLimitAccessibilityService : AccessibilityService() {
    private sealed interface TrackingCommand {
        data class WindowChanged(
            val timestampEpochMillis: Long,
            val packageName: String?,
            val className: String?,
            val deviceState: DeviceStateSnapshot,
        ) : TrackingCommand

        data class ScreenChanged(
            val timestampEpochMillis: Long,
            val signal: ScreenSignal,
            val deviceState: DeviceStateSnapshot,
        ) : TrackingCommand

        data class ConfigurationChanged(
            val snapshot: ConfigurationSnapshot,
        ) : TrackingCommand

        data class ConfirmBlock(
            val id: String,
            val variant: EnforcementEvaluator.GrantVariant,
        ) : TrackingCommand

        data class AbortBlock(val id: String) : TrackingCommand

        data class ReevaluateHardBlock(val id: String) : TrackingCommand

        data object Tick : TrackingCommand

        data class HealthProbe(val acknowledge: () -> Unit) : TrackingCommand
    }

    private data class ActiveBlock(
        val id: String,
        val packageName: String,
        val appLabel: String,
        val evaluation: EnforcementEvaluator.Evaluation,
        val isHardBlock: Boolean,
    )

    private data class EnforcementOutcome(
        val finalizedSessions: List<UsageEngine.SessionSnapshot> = emptyList(),
        val rawDecisionSuffix: String = "",
        val mustPersist: Boolean = false,
    )

    private data class ObservedWindow(
        val packageName: String?,
        val className: String?,
    )

    private data class ForegroundReconciliationOutcome(
        val change: UsageEngine.Change,
        val resolution: ForegroundReconciliationPolicy.Resolution,
    ) {
        val rawDecisionSuffix: String
            get() = when (resolution.action) {
                ForegroundReconciliationPolicy.Action.KEEP -> ""
                ForegroundReconciliationPolicy.Action.TRACK ->
                    "|RECONCILED_TRACK:${resolution.observedPackage}:${resolution.reason}"

                ForegroundReconciliationPolicy.Action.CLEAR ->
                    "|RECONCILED_CLEAR:${resolution.observedPackage}:${resolution.reason}"
            }
    }

    private val clock: Clock = Clock.systemDefaultZone()
    private var currentZoneId: ZoneId = ZoneId.systemDefault()
    private val engine = UsageEngine(clock, currentZoneId)
    private var evaluator = EnforcementEvaluator(currentZoneId)
    private val dailyResetCoordinator = DailyResetCoordinator(clock) { ZoneId.systemDefault() }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Channel<TrackingCommand>(capacity = Channel.UNLIMITED)

    private lateinit var repository: UsageRepository
    private lateinit var configRepository: ConfigRepository
    private lateinit var packageClassifier: ForegroundPackageClassifier
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var powerManager: PowerManager
    private lateinit var overlays: EnforcementOverlayController
    private lateinit var notifier: EnforcementNotifier
    private lateinit var healthStore: ServiceHealthStore

    private var currentConfiguration = ConfigurationSnapshot()
    private var targetGrants: Map<LimitTarget, EnforcementEvaluator.GrantTotals> = emptyMap()
    private var grantDate: LocalDate? = null
    private val deliveredWarningKeys = mutableSetOf<String>()
    private var activeBlock: ActiveBlock? = null
    private var commandProcessor: Job? = null
    private var configurationCollector: Job? = null
    private var ticker: Job? = null
    private var tickerIntervalMillis: Long? = null
    private var hardBlockExpiryJob: Job? = null
    private var healthHeartbeatJob: Job? = null
    private var receiverRegistered = false
    private var lastFlushEpochMillis = 0L
    private var shutdownFlushed = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_TIMEZONE_CHANGED) {
                commands.trySend(TrackingCommand.Tick)
                return
            }
            val signal = when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> ScreenSignal.SCREEN_ON
                Intent.ACTION_SCREEN_OFF -> ScreenSignal.SCREEN_OFF
                Intent.ACTION_USER_PRESENT -> ScreenSignal.USER_PRESENT
                else -> return
            }
            commands.trySend(
                TrackingCommand.ScreenChanged(
                    timestampEpochMillis = clock.millis(),
                    signal = signal,
                    deviceState = readDeviceState(),
                ),
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!::healthStore.isInitialized) {
            healthStore = ServiceHealthStore(applicationContext)
        }
        shutdownFlushed = false
        healthStore.markConnected(clock.millis())
        startHealthHeartbeat()
        ForegroundAnchorService.start(applicationContext)
        WatchdogScheduler.schedulePeriodic(applicationContext)
        if (commandProcessor != null) {
            registerHealthProbe()
            commands.trySend(TrackingCommand.Tick)
            return
        }

        repository = UsageRepository.get(applicationContext)
        configRepository = ConfigRepository.get(applicationContext)
        packageClassifier = ForegroundPackageClassifier(applicationContext)
        keyguardManager = getSystemService(KeyguardManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        overlays = EnforcementOverlayController(applicationContext, serviceScope)
        notifier = EnforcementNotifier(applicationContext).also { it.createChannels() }
        val initialDeviceState = readDeviceState()
        registerScreenReceiver()
        commandProcessor = serviceScope.launch {
            val today = dailyResetCoordinator.poll().date
            currentConfiguration = configRepository.getConfiguration()
            val restoredSessions = repository.restoreSessions()
            engine.restoreCounters(repository.restoreCounters(today))
            engine.restoreSessions(restoredSessions)
            restoreTargetGrants(today)
            lastFlushEpochMillis = clock.millis()
            val initialChange = engine.onDeviceStateChanged(initialDeviceState)
            val resyncChange = resyncForeground(restoredSessions, initialDeviceState)
            persist(
                UsageEngine.Change(
                    finalizedSessions =
                        initialChange.finalizedSessions + resyncChange.finalizedSessions,
                    sessionStarted = resyncChange.sessionStarted,
                ),
            )

            configurationCollector = serviceScope.launch {
                configRepository.observeConfiguration().collect { snapshot ->
                    commands.send(TrackingCommand.ConfigurationChanged(snapshot))
                }
            }
            updateTicker()

            for (command in commands) {
                ensureDayContext()
                when (command) {
                    is TrackingCommand.WindowChanged -> handleWindowChanged(command)
                    is TrackingCommand.ScreenChanged -> handleScreenChanged(command)
                    is TrackingCommand.ConfigurationChanged -> handleConfigurationChanged(command)
                    is TrackingCommand.ConfirmBlock ->
                        handleConfirmBlock(command.id, command.variant)
                    is TrackingCommand.AbortBlock -> handleAbortBlock(command.id)
                    is TrackingCommand.ReevaluateHardBlock ->
                        handleReevaluateHardBlock(command.id)
                    TrackingCommand.Tick -> handleTick()
                    is TrackingCommand.HealthProbe -> Unit
                }
                healthStore.markCommandProcessed(clock.millis())
                if (command is TrackingCommand.HealthProbe) command.acknowledge()
                updateTicker()
            }
        }
        registerHealthProbe()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        commands.trySend(
            TrackingCommand.WindowChanged(
                timestampEpochMillis = clock.millis(),
                packageName = event.packageName?.toString(),
                className = event.className?.toString(),
                deviceState = readDeviceState(),
            ),
        )
    }

    override fun onInterrupt() {
        commands.trySend(
            TrackingCommand.WindowChanged(
                timestampEpochMillis = clock.millis(),
                packageName = packageName,
                className = "ACCESSIBILITY_SERVICE_INTERRUPTED",
                deviceState = readDeviceState(),
            ),
        )
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityServiceHealthProbe.unregister()
        flushForShutdown()
        healthHeartbeatJob?.cancel()
        healthHeartbeatJob = null
        if (::healthStore.isInitialized) healthStore.markDisconnected(clock.millis())
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityServiceHealthProbe.unregister()
        flushForShutdown()
        if (::healthStore.isInitialized) healthStore.markDisconnected(clock.millis())
        ticker?.cancel()
        hardBlockExpiryJob?.cancel()
        healthHeartbeatJob?.cancel()
        configurationCollector?.cancel()
        if (::overlays.isInitialized) overlays.dismissAll()
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        commands.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    /** Best-effort final flush complements the seven-second cadence on orderly restarts. */
    private fun flushForShutdown() {
        if (shutdownFlushed || !::repository.isInitialized || commandProcessor == null) return
        shutdownFlushed = true
        val change = engine.tick()
        val now = clock.millis()
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(SHUTDOWN_FLUSH_TIMEOUT_MILLIS) {
                repository.persist(
                    snapshot = engine.snapshot(),
                    finalizedSessions = change.finalizedSessions,
                    persistedAtEpochMillis = now,
                )
            }
        }
    }

    private fun startHealthHeartbeat() {
        healthHeartbeatJob?.cancel()
        healthHeartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(HEALTH_HEARTBEAT_INTERVAL_MILLIS)
                if (commandProcessor?.isActive != true) break
                healthStore.markHeartbeat(clock.millis())
            }
        }
    }

    private fun registerHealthProbe() {
        AccessibilityServiceHealthProbe.register { acknowledge ->
            commands.trySend(TrackingCommand.HealthProbe(acknowledge)).isSuccess
        }
    }

    private suspend fun handleWindowChanged(command: TrackingCommand.WindowChanged) {
        val decision = packageClassifier.classify(command.packageName, command.className)
        val screenAvailable = ScreenAvailabilityPolicy.isScreenAvailable(command.deviceState)
        val reconciliation = if (decision.action == ForegroundPackageClassifier.Action.IGNORE_OVERLAY) {
            reconcileForeground(command.deviceState)
        } else {
            null
        }
        val change = when (decision.action) {
            ForegroundPackageClassifier.Action.TRACK ->
                engine.onForegroundChanged(decision.packageName, command.deviceState)

            ForegroundPackageClassifier.Action.CLEAR_FOREGROUND ->
                engine.onForegroundChanged(null, command.deviceState)

            ForegroundPackageClassifier.Action.IGNORE_OVERLAY ->
                checkNotNull(reconciliation).change
        }

        val correctedToTrackedPackage =
            reconciliation?.resolution?.action == ForegroundReconciliationPolicy.Action.TRACK
        val foregroundPackage = engine.snapshot().foregroundPackage
        val outcome = if (
            foregroundPackage != null &&
            (decision.action == ForegroundPackageClassifier.Action.TRACK || correctedToTrackedPackage) &&
            screenAvailable
        ) {
            enforceForeground(foregroundPackage, change, command.deviceState)
        } else {
            if (activeBlock == null) overlays.dismissMinuteCountdown()
            EnforcementOutcome()
        }

        // Enforcement above intentionally precedes Room I/O to minimize any target-app flash.
        repository.insertRawEvent(
            RawUsageEventEntity(
                timestampEpochMillis = command.timestampEpochMillis,
                eventType = "TYPE_WINDOW_STATE_CHANGED",
                reportedPackage = command.packageName,
                resolvedPackage = decision.packageName,
                decision = decision.reason + reconciliation?.rawDecisionSuffix.orEmpty() +
                    outcome.rawDecisionSuffix,
                className = command.className,
                screenOn = screenAvailable,
            ),
        )
        persist(
            UsageEngine.Change(
                finalizedSessions = change.finalizedSessions + outcome.finalizedSessions,
                sessionStarted = change.sessionStarted,
            ),
        )
    }

    private suspend fun handleScreenChanged(command: TrackingCommand.ScreenChanged) {
        val screenAvailable = ScreenAvailabilityPolicy.isScreenAvailable(command.deviceState)
        val change = engine.onDeviceStateChanged(command.deviceState)
        val foregroundPackage = engine.snapshot().foregroundPackage
        val outcome = if (screenAvailable && foregroundPackage != null) {
            enforceForeground(foregroundPackage, change, command.deviceState)
        } else {
            overlays.dismissMinuteCountdown()
            EnforcementOutcome()
        }
        repository.insertRawEvent(
            RawUsageEventEntity(
                timestampEpochMillis = command.timestampEpochMillis,
                eventType = command.signal.rawEventType,
                reportedPackage = null,
                resolvedPackage = engine.snapshot().foregroundPackage,
                decision = when {
                    !command.deviceState.isInteractive -> "DEVICE_NOT_INTERACTIVE"
                    command.deviceState.isKeyguardLocked -> "WAITING_FOR_UNLOCK"
                    else -> "DEVICE_AVAILABLE${outcome.rawDecisionSuffix}"
                },
                className = null,
                screenOn = screenAvailable,
            ),
        )
        persist(
            UsageEngine.Change(
                finalizedSessions = change.finalizedSessions + outcome.finalizedSessions,
                sessionStarted = change.sessionStarted,
            ),
        )
    }

    private suspend fun handleConfigurationChanged(command: TrackingCommand.ConfigurationChanged) {
        currentConfiguration = command.snapshot
        val block = activeBlock
        if (block != null) {
            val evaluation = evaluator.evaluate(
                now = clock.instant(),
                packageName = block.packageName,
                configuration = currentConfiguration,
                counters = engine.snapshot().counters,
                targetGrants = targetGrants,
            )
            val remainsBlocked = if (block.isHardBlock) {
                evaluation.monitored && evaluation.hardBlock != null
            } else {
                evaluation.monitored && evaluation.hardBlock == null && evaluation.requiresFriction
            }
            if (!remainsBlocked) {
                hardBlockExpiryJob?.cancel()
                hardBlockExpiryJob = null
                overlays.dismissBlock()
                activeBlock = null
            } else if (block.isHardBlock) {
                commands.trySend(TrackingCommand.ReevaluateHardBlock(block.id))
            }
        }
        val foregroundPackage = engine.snapshot().foregroundPackage
        if (foregroundPackage == null) {
            overlays.dismissMinuteCountdown()
        } else {
            val outcome = enforceForeground(
                foregroundPackage,
                UsageEngine.Change(),
                readDeviceState(),
            )
            if (outcome.mustPersist || outcome.finalizedSessions.isNotEmpty()) {
                persist(UsageEngine.Change(finalizedSessions = outcome.finalizedSessions))
            }
        }
    }

    private suspend fun handleTick() {
        val deviceState = readDeviceState()
        val reconciliation = reconcileForeground(deviceState)
        val tickChange = engine.tick()
        val change = UsageEngine.Change(
            finalizedSessions =
                reconciliation.change.finalizedSessions + tickChange.finalizedSessions,
            sessionStarted = reconciliation.change.sessionStarted,
        )
        if (reconciliation.resolution.action != ForegroundReconciliationPolicy.Action.KEEP) {
            repository.insertRawEvent(
                RawUsageEventEntity(
                    timestampEpochMillis = clock.millis(),
                    eventType = "FOREGROUND_RECONCILIATION",
                    reportedPackage = reconciliation.resolution.observedPackage,
                    resolvedPackage = reconciliation.resolution.packageName,
                    decision = reconciliation.resolution.action.name + ":" +
                        reconciliation.resolution.reason,
                    className = null,
                    screenOn = ScreenAvailabilityPolicy.isScreenAvailable(deviceState),
                ),
            )
        }
        val foregroundPackage = engine.snapshot().foregroundPackage
        val outcome = if (foregroundPackage != null) {
            enforceForeground(foregroundPackage, change, deviceState)
        } else {
            if (activeBlock == null) overlays.dismissMinuteCountdown()
            EnforcementOutcome()
        }
        val now = clock.millis()
        if (
            outcome.mustPersist ||
            change.finalizedSessions.isNotEmpty() ||
            outcome.finalizedSessions.isNotEmpty() ||
            now - lastFlushEpochMillis >= FLUSH_INTERVAL_MILLIS
        ) {
            persist(
                UsageEngine.Change(
                    finalizedSessions = change.finalizedSessions + outcome.finalizedSessions,
                ),
            )
        }
    }

    private suspend fun enforceForeground(
        packageName: String,
        usageChange: UsageEngine.Change,
        deviceState: DeviceStateSnapshot,
    ): EnforcementOutcome {
        ensureGrantDate(dailyResetCoordinator.poll().date)
        activeBlock?.let { block ->
            if (block.packageName == packageName) {
                val finalized = pauseRejectedForeground(packageName, usageChange, deviceState)
                performGlobalAction(GLOBAL_ACTION_HOME)
                return EnforcementOutcome(
                    finalizedSessions = finalized,
                    rawDecisionSuffix = "|BLOCK_REASSERTED",
                    mustPersist = true,
                )
            }
            return EnforcementOutcome()
        }

        val evaluation = evaluator.evaluate(
            now = clock.instant(),
            packageName = packageName,
            configuration = currentConfiguration,
            counters = engine.snapshot().counters,
            targetGrants = targetGrants,
        )
        if (!evaluation.monitored) {
            overlays.dismissMinuteCountdown()
            return EnforcementOutcome()
        }

        val appLabel = currentConfiguration.apps
            .firstOrNull { it.packageName == packageName }
            ?.label
            ?: packageName
        evaluation.hardBlock?.let { hardBlock ->
            val finalized = pauseRejectedForeground(packageName, usageChange, deviceState)
            activateHardBlock(packageName, appLabel, evaluation, hardBlock)
            return EnforcementOutcome(
                finalizedSessions = finalized,
                rawDecisionSuffix = "|BLOCKED_TIME_WINDOW",
                mustPersist = true,
            )
        }

        if (evaluation.requiresFriction) {
            val finalized = pauseRejectedForeground(packageName, usageChange, deviceState)
            val block = ActiveBlock(
                id = UUID.randomUUID().toString(),
                packageName = packageName,
                appLabel = appLabel,
                evaluation = evaluation,
                isHardBlock = false,
            )
            activeBlock = block
            performGlobalAction(GLOBAL_ACTION_HOME)
            val labels = confirmationLabels(evaluation.breaches)
            val shown = overlays.showFrictionBlock(
                title = "$appLabel hat das Tageslimit erreicht",
                reason = frictionReason(evaluation.breaches),
                smallConfirmationLabel = labels.first,
                largeConfirmationLabel = labels.second,
                onConfirmSmall = {
                    commands.trySend(
                        TrackingCommand.ConfirmBlock(
                            block.id,
                            EnforcementEvaluator.GrantVariant.SMALL,
                        ),
                    )
                },
                onConfirmLarge = {
                    commands.trySend(
                        TrackingCommand.ConfirmBlock(
                            block.id,
                            EnforcementEvaluator.GrantVariant.LARGE,
                        ),
                    )
                },
                onAbort = { commands.trySend(TrackingCommand.AbortBlock(block.id)) },
            )
            performGlobalAction(GLOBAL_ACTION_HOME)
            if (!shown) {
                activeBlock = null
                notifier.notifyOverlayUnavailable()
            }
            return EnforcementOutcome(
                finalizedSessions = finalized,
                rawDecisionSuffix = "|BLOCKED_FRICTION",
                mustPersist = true,
            )
        }

        deliverWarnings(evaluation, packageName)
        val countdown = evaluation.countdownSeconds
        if (countdown == null) {
            overlays.dismissMinuteCountdown()
        } else if (!overlays.showMinuteCountdown(countdown, appLabel)) {
            notifier.notifyOverlayUnavailable()
        }
        return EnforcementOutcome()
    }

    private fun pauseRejectedForeground(
        packageName: String,
        usageChange: UsageEngine.Change,
        deviceState: DeviceStateSnapshot,
    ): List<UsageEngine.SessionSnapshot> {
        if (usageChange.sessionStarted) {
            engine.rejectJustStartedOpening(packageName)
            return emptyList()
        }
        return engine.onForegroundChanged(null, deviceState).finalizedSessions
    }

    private fun activateHardBlock(
        packageName: String,
        appLabel: String,
        evaluation: EnforcementEvaluator.Evaluation,
        hardBlock: EnforcementEvaluator.HardBlock,
    ) {
        hardBlockExpiryJob?.cancel()
        val block = ActiveBlock(
            id = UUID.randomUUID().toString(),
            packageName = packageName,
            appLabel = appLabel,
            evaluation = evaluation,
            isHardBlock = true,
        )
        activeBlock = block
        performGlobalAction(GLOBAL_ACTION_HOME)
        val shown = overlays.showHardBlock(
            title = "$appLabel ist gerade gesperrt",
            reason = hardBlockReason(hardBlock.blockedTargets),
            nextRelease = formatNextRelease(hardBlock.nextAllowedAt),
            onAbort = { commands.trySend(TrackingCommand.AbortBlock(block.id)) },
        )
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (shown) {
            scheduleHardBlockReevaluation(block, hardBlock.nextAllowedAt)
        } else {
            activeBlock = null
            notifier.notifyOverlayUnavailable()
        }
    }

    private fun scheduleHardBlockReevaluation(block: ActiveBlock, nextAllowedAt: Instant?) {
        hardBlockExpiryJob?.cancel()
        if (nextAllowedAt == null) return
        hardBlockExpiryJob = serviceScope.launch {
            val remainingMillis = (nextAllowedAt.toEpochMilli() - clock.millis()).coerceAtLeast(250L)
            delay(remainingMillis)
            commands.send(TrackingCommand.ReevaluateHardBlock(block.id))
        }
    }

    private suspend fun handleReevaluateHardBlock(id: String) {
        val block = activeBlock?.takeIf { it.id == id && it.isHardBlock } ?: return
        ensureGrantDate(dailyResetCoordinator.poll().date)
        val evaluation = evaluator.evaluate(
            now = clock.instant(),
            packageName = block.packageName,
            configuration = currentConfiguration,
            counters = engine.snapshot().counters,
            targetGrants = targetGrants,
        )
        val hardBlock = evaluation.hardBlock
        if (!evaluation.monitored || hardBlock == null) {
            hardBlockExpiryJob?.cancel()
            hardBlockExpiryJob = null
            overlays.dismissBlock()
            activeBlock = null
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        activateHardBlock(block.packageName, block.appLabel, evaluation, hardBlock)
    }

    private suspend fun handleConfirmBlock(
        id: String,
        variant: EnforcementEvaluator.GrantVariant,
    ) {
        val block = activeBlock?.takeIf { it.id == id && !it.isHardBlock } ?: return
        restoreTargetGrants(block.evaluation.date)
        val deltas = evaluator.frictionGrantDeltas(block.evaluation.breaches, variant)
        if (deltas.isEmpty()) return
        targetGrants = evaluator.applyGrantDeltas(targetGrants, deltas)
        val grantedMinutes = if (deltas.any { it.grantedMinutes > 0 }) variant.minutes else 0
        val grantedOpenings = if (deltas.any { it.grantedOpenings > 0 }) variant.openings else 0
        engine.recordGrantedExtensions(
            packageName = block.packageName,
            date = block.evaluation.date,
            grantedMinutes = grantedMinutes,
            grantedOpenings = grantedOpenings,
        )

        val now = clock.millis()
        repository.persist(
            snapshot = engine.snapshot(),
            finalizedSessions = emptyList(),
            persistedAtEpochMillis = now,
            targetGrants = targetGrants.toRepositorySnapshots(block.evaluation.date),
            frictionEvent = frictionEvent(block, deltas, now, grantedMinutes, grantedOpenings),
        )
        lastFlushEpochMillis = now
        overlays.dismissBlock()
        activeBlock = null
        val today = dailyResetCoordinator.poll().date
        if (today != block.evaluation.date) {
            restoreTargetGrants(today)
            val currentEvaluation = evaluator.evaluate(
                now = clock.instant(),
                packageName = block.packageName,
                configuration = currentConfiguration,
                counters = engine.snapshot().counters,
                targetGrants = targetGrants,
            )
            currentEvaluation.hardBlock?.let { hardBlock ->
                activateHardBlock(
                    block.packageName,
                    block.appLabel,
                    currentEvaluation,
                    hardBlock,
                )
                return
            }
        }
        launchPackage(block.packageName)
    }

    private fun handleAbortBlock(id: String) {
        if (activeBlock?.id != id) return
        hardBlockExpiryJob?.cancel()
        hardBlockExpiryJob = null
        overlays.dismissBlock()
        activeBlock = null
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun deliverWarnings(
        evaluation: EnforcementEvaluator.Evaluation,
        packageName: String,
    ) {
        evaluation.warnings.forEach { warning ->
            val key = buildString {
                append(evaluation.date)
                append(':')
                append(warning.target.storageKey())
                append(':')
                append(warning.kind)
                append(':')
                append(warning.effectiveLimit)
            }
            if (!deliveredWarningKeys.add(key)) return@forEach
            val label = targetLabel(warning.target, packageName)
            when (warning.kind) {
                EnforcementEvaluator.WarningKind.MINUTES_EIGHTY_PERCENT ->
                    notifier.notifyMinuteBudgetWarning(key, label)

                EnforcementEvaluator.WarningKind.ONE_OPENING_REMAINING ->
                    notifier.notifyOpeningBudgetWarning(key, label)
            }
        }
    }

    private suspend fun ensureGrantDate(date: LocalDate) {
        if (grantDate != date) restoreTargetGrants(date)
    }

    /** Switches active configuration, counters, grants and warning dedupe as one queue step. */
    private suspend fun ensureDayContext() {
        val transition = dailyResetCoordinator.poll()
        if (!transition.changed) return

        if (transition.zoneChanged) {
            val zoneChange = engine.updateZone(transition.zoneId)
            currentZoneId = transition.zoneId
            evaluator = EnforcementEvaluator(currentZoneId)
            if (zoneChange.finalizedSessions.isNotEmpty()) persist(zoneChange)
            activeBlock?.takeIf(ActiveBlock::isHardBlock)?.let { block ->
                commands.trySend(TrackingCommand.ReevaluateHardBlock(block.id))
            }
        }
        if (transition.dateChanged) {
            currentConfiguration = configRepository.getConfiguration()
            engine.synchronizeCounters(repository.restoreCounters(transition.date))
            restoreTargetGrants(transition.date)
            activeBlock?.let {
                hardBlockExpiryJob?.cancel()
                hardBlockExpiryJob = null
                overlays.dismissBlock()
                activeBlock = null
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    private suspend fun restoreTargetGrants(date: LocalDate) {
        targetGrants = repository.restoreTargetGrants(date)
            .mapNotNull { stored ->
                stored.toLimitTarget()?.let { target ->
                    target to EnforcementEvaluator.GrantTotals(
                        grantedMinutes = stored.grantedMinutes,
                        grantedOpenings = stored.grantedOpenings,
                    )
                }
            }
            .toMap()
        grantDate = date
        deliveredWarningKeys.clear()
    }

    private suspend fun persist(change: UsageEngine.Change) {
        val now = clock.millis()
        repository.persist(
            snapshot = engine.snapshot(),
            finalizedSessions = change.finalizedSessions,
            persistedAtEpochMillis = now,
        )
        lastFlushEpochMillis = now
    }

    private fun frictionEvent(
        block: ActiveBlock,
        deltas: List<EnforcementEvaluator.GrantDelta>,
        timestampEpochMillis: Long,
        grantedMinutes: Int,
        grantedOpenings: Int,
    ): UsageRepository.FrictionEventRecord {
        val targets = deltas.map(EnforcementEvaluator.GrantDelta::target).distinct()
        val targetType = if (targets.size == 1) targets.single().storageType() else {
            UsageRepository.TARGET_MULTIPLE
        }
        val targetId = if (targets.size == 1) targets.single().storageId() else {
            targets.map { it.storageKey() }.sorted().joinToString(",")
        }
        val ruleTypes = block.evaluation.breaches.map { it.type }.distinct()
        val ruleType = if (ruleTypes.size == 1) ruleTypes.single().name else {
            UsageRepository.RULE_MULTIPLE
        }
        return UsageRepository.FrictionEventRecord(
            id = block.id,
            date = block.evaluation.date,
            timestampEpochMillis = timestampEpochMillis,
            triggeringPackageName = block.packageName,
            targetType = targetType,
            targetId = targetId,
            ruleType = ruleType,
            grantedOpenings = grantedOpenings,
            grantedMinutes = grantedMinutes,
        )
    }

    private fun Map<LimitTarget, EnforcementEvaluator.GrantTotals>.toRepositorySnapshots(
        date: LocalDate,
    ): List<UsageRepository.TargetGrantSnapshot> = entries.map { (target, grants) ->
        UsageRepository.TargetGrantSnapshot(
            date = date,
            targetType = target.storageType(),
            targetId = target.storageId(),
            grantedOpenings = grants.grantedOpenings,
            grantedMinutes = grants.grantedMinutes,
        )
    }

    private fun UsageRepository.TargetGrantSnapshot.toLimitTarget(): LimitTarget? = when (targetType) {
        UsageRepository.TARGET_APP -> LimitTarget.App(targetId)
        UsageRepository.TARGET_GROUP -> targetId.toLongOrNull()?.let(LimitTarget::Group)
        else -> null
    }

    private fun targetLabel(target: LimitTarget, triggeringPackage: String): String = when (target) {
        is LimitTarget.App -> currentConfiguration.apps
            .firstOrNull { it.packageName == target.packageName }
            ?.label
            ?: triggeringPackage

        is LimitTarget.Group -> currentConfiguration.groups
            .firstOrNull { it.id == target.groupId }
            ?.name
            ?.let { "Gruppe $it" }
            ?: "App-Gruppe"
    }

    private fun frictionReason(breaches: List<EnforcementEvaluator.Breach>): String {
        val types = breaches.map { it.type }.toSet()
        val limitText = when {
            LimitRuleType.MINUTES in types && LimitRuleType.OPENINGS in types ->
                "Zeit- und Öffnungslimit sind erreicht."

            LimitRuleType.MINUTES in types -> "Das Zeitlimit ist erreicht."
            else -> "Das Öffnungslimit ist erreicht."
        }
        val targets = breaches
            .map { targetLabel(it.target, "App") }
            .distinct()
            .joinToString(", ")
        return "$limitText Betroffen: $targets"
    }

    /** First = SMALL variant label, second = LARGE variant label. */
    private fun confirmationLabels(
        breaches: List<EnforcementEvaluator.Breach>,
    ): Pair<String, String> {
        val types = breaches.map { it.type }.toSet()
        val small = EnforcementEvaluator.GrantVariant.SMALL
        val large = EnforcementEvaluator.GrantVariant.LARGE
        return when {
            LimitRuleType.MINUTES in types && LimitRuleType.OPENINGS in types -> Pair(
                "+${small.minutes} Minute und +${small.openings} Öffnung freigeben",
                "+${large.minutes} Minuten und +${large.openings} Öffnungen freigeben",
            )

            LimitRuleType.MINUTES in types -> Pair(
                "+${small.minutes} Minute freigeben",
                "+${large.minutes} Minuten freigeben",
            )

            else -> Pair(
                "+${small.openings} Öffnung freigeben",
                "+${large.openings} Öffnungen freigeben",
            )
        }
    }

    private fun hardBlockReason(targets: Set<LimitTarget>): String {
        val labels = targets.map { targetLabel(it, "App") }.distinct().joinToString(", ")
        return "Außerhalb des erlaubten Zeitfensters für $labels. " +
            "Diese Sperre hat keine Friction-Freigabe."
    }

    private fun formatNextRelease(nextAllowedAt: Instant?): String = if (nextAllowedAt == null) {
        "Keine Freigabe im konfigurierten Wochenplan"
    } else {
        val formatter = DateTimeFormatter.ofPattern("EEE, dd.MM., HH:mm 'Uhr'", Locale.GERMANY)
        "Nächste Freigabe: ${formatter.format(nextAllowedAt.atZone(currentZoneId))}"
    }

    private fun launchPackage(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
        )
        try {
            startActivity(intent)
        } catch (_: RuntimeException) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    /**
     * Reconnects tracking after a service/process restart without waiting for another window
     * transition. The active accessibility window is preferred; a previously active persisted
     * session is a short-lived fallback when the system does not expose a root window.
     */
    private fun resyncForeground(
        restoredSessions: List<UsageEngine.SessionSnapshot>,
        deviceState: DeviceStateSnapshot,
    ): UsageEngine.Change {
        if (!ScreenAvailabilityPolicy.isScreenAvailable(deviceState)) return UsageEngine.Change()
        val activeWindow = try {
            rootInActiveWindow?.let { root ->
                root.packageName?.toString() to root.className?.toString()
            }
        } catch (_: RuntimeException) {
            null
        }
        val persistedActivePackage = restoredSessions
            .asSequence()
            .filter { it.inactiveSinceEpochMillis == null }
            .maxByOrNull { it.endedAtEpochMillis }
            ?.packageName
        val candidates = listOfNotNull(
            activeWindow,
            persistedActivePackage?.let { it to null },
        ).distinct()
        candidates.forEach { (candidate, className) ->
            val decision = packageClassifier.classify(candidate, className)
            when (decision.action) {
                ForegroundPackageClassifier.Action.TRACK -> return engine.onForegroundChanged(
                    decision.packageName,
                    deviceState,
                )

                ForegroundPackageClassifier.Action.CLEAR_FOREGROUND ->
                    return engine.onForegroundChanged(null, deviceState)

                ForegroundPackageClassifier.Action.IGNORE_OVERLAY -> Unit
            }
        }
        return UsageEngine.Change()
    }

    private fun readDeviceState(): DeviceStateSnapshot = DeviceStateSnapshot(
        isInteractive = powerManager.isInteractive,
        isKeyguardLocked = keyguardManager.isKeyguardLocked,
    )

    /**
     * Corrects a stale engine foreground only when the interactive-window stack provides a
     * decisive user-app or launcher window. Transient overlays alone never clear the session.
     */
    private fun reconcileForeground(
        deviceState: DeviceStateSnapshot,
    ): ForegroundReconciliationOutcome {
        val stateChange = engine.onDeviceStateChanged(deviceState)
        val currentPackage = engine.snapshot().foregroundPackage
        if (!ScreenAvailabilityPolicy.isScreenAvailable(deviceState) || currentPackage == null) {
            return ForegroundReconciliationOutcome(
                change = stateChange,
                resolution = ForegroundReconciliationPolicy.Resolution(
                    action = ForegroundReconciliationPolicy.Action.KEEP,
                    packageName = currentPackage,
                    observedPackage = null,
                    reason = "NO_ACTIVE_TRACKED_FOREGROUND",
                ),
            )
        }

        val candidates = readObservedWindows().map { observed ->
            val decision = packageClassifier.classify(observed.packageName, observed.className)
            ForegroundReconciliationPolicy.Candidate(
                packageName = decision.packageName ?: observed.packageName,
                className = observed.className,
                action = when (decision.action) {
                    ForegroundPackageClassifier.Action.TRACK ->
                        ForegroundReconciliationPolicy.CandidateAction.TRACK

                    ForegroundPackageClassifier.Action.CLEAR_FOREGROUND ->
                        ForegroundReconciliationPolicy.CandidateAction.CLEAR

                    ForegroundPackageClassifier.Action.IGNORE_OVERLAY ->
                        ForegroundReconciliationPolicy.CandidateAction.IGNORE
                },
                reason = decision.reason,
            )
        }
        val resolution = ForegroundReconciliationPolicy.resolve(currentPackage, candidates)
        val foregroundChange = when (resolution.action) {
            ForegroundReconciliationPolicy.Action.KEEP -> UsageEngine.Change()
            ForegroundReconciliationPolicy.Action.TRACK ->
                engine.onForegroundChanged(resolution.packageName, deviceState)

            ForegroundReconciliationPolicy.Action.CLEAR ->
                engine.onForegroundChanged(null, deviceState)
        }
        return ForegroundReconciliationOutcome(
            change = UsageEngine.Change(
                finalizedSessions =
                    stateChange.finalizedSessions + foregroundChange.finalizedSessions,
                sessionStarted = stateChange.sessionStarted || foregroundChange.sessionStarted,
            ),
            resolution = resolution,
        )
    }

    /** Reads only package/class metadata, ordered from the active top application window down. */
    private fun readObservedWindows(): List<ObservedWindow> {
        val observed = mutableListOf<ObservedWindow>()
        val interactiveWindows = try {
            windows
        } catch (_: RuntimeException) {
            emptyList()
        }
        interactiveWindows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.isActive }
                    .thenByDescending { it.isFocused }
                    .thenByDescending { it.layer },
            )
            .forEach { window ->
                val candidate = try {
                    window.root?.let { root ->
                        ObservedWindow(
                            packageName = root.packageName?.toString(),
                            className = root.className?.toString(),
                        )
                    }
                } catch (_: RuntimeException) {
                    null
                }
                if (candidate != null) observed += candidate
            }

        val activeRoot = try {
            rootInActiveWindow?.let { root ->
                ObservedWindow(
                    packageName = root.packageName?.toString(),
                    className = root.className?.toString(),
                )
            }
        } catch (_: RuntimeException) {
            null
        }
        if (activeRoot != null) observed += activeRoot
        return observed.distinctBy { it.packageName to it.className }
    }

    private fun updateTicker() {
        val snapshot = engine.snapshot()
        val foregroundPackage = snapshot.foregroundPackage
        val desiredInterval = TrackingCadencePolicy.intervalMillis(
            hasLiveSessions = engine.requiresTicking(),
            screenOn = snapshot.screenOn,
            foregroundPackage = foregroundPackage,
            monitoredPackages = currentConfiguration.apps.mapTo(mutableSetOf()) { it.packageName },
        )
        if (desiredInterval != null) {
            if (ticker?.isActive == true && tickerIntervalMillis == desiredInterval) return
            ticker?.cancel()
            tickerIntervalMillis = desiredInterval
            ticker = serviceScope.launch {
                while (isActive) {
                    delay(desiredInterval)
                    commands.send(TrackingCommand.Tick)
                }
            }
        } else {
            ticker?.cancel()
            ticker = null
            tickerIntervalMillis = null
        }
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun LimitTarget.storageType(): String = when (this) {
        is LimitTarget.App -> UsageRepository.TARGET_APP
        is LimitTarget.Group -> UsageRepository.TARGET_GROUP
    }

    private fun LimitTarget.storageId(): String = when (this) {
        is LimitTarget.App -> packageName
        is LimitTarget.Group -> groupId.toString()
    }

    private fun LimitTarget.storageKey(): String = "${storageType()}:${storageId()}"

    private companion object {
        const val FLUSH_INTERVAL_MILLIS = 7_000L
        const val HEALTH_HEARTBEAT_INTERVAL_MILLIS = 60_000L
        const val SHUTDOWN_FLUSH_TIMEOUT_MILLIS = 2_500L
    }
}
