package com.shilapi.xcertplay.autoconnect

import android.content.Context

/** The thin adapter between the session and the existing connection stack. */
internal interface AutoConnectConnector {
    /** Starts the connection for [request]; returns false when the platform refused to start. */
    fun start(request: AutoConnectRequest): Boolean

    /** Stops a session that this connector owns, and only that one. */
    fun cancelSession(sessionId: Long)
}

/**
 * The one automatic session state machine (design §6.2, §7.1).
 *
 * A process singleton: at most one non-terminal session exists at a time (invariant I1). Terminal
 * states never transition again (I2); only [offer] or [retryFromUi] may create a new session id.
 * All mutations happen on the main thread; the gate and the budget are pure.
 */
internal object AutoConnectSession {
    private class Active(
        val id: Long,
        val trigger: AutoConnectTrigger,
        val mode: com.shilapi.xcertplay.orchestration.WirelessHotspotMode,
        val tier: AutoConnectTier,
        val budget: AutoConnectBudget,
        var stage: AutoConnectStage = AutoConnectStage.PREPARING_NETWORK,
    )

    private var appContext: Context? = null
    private var connector: AutoConnectConnector? = null
    private var active: Active? = null
    private var currentState: AutoConnectState = AutoConnectState.Idle
    private var nextId = 1L
    private var lastRequest: AutoConnectRequest? = null
    private val listeners = mutableListOf<(AutoConnectState) -> Unit>()

    /** Injectable clock; the production adapter passes SystemClock.elapsedRealtime(). */
    var clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    fun initialize(context: Context, connector: AutoConnectConnector) {
        appContext = context.applicationContext
        this.connector = connector
        resetState()
    }

    /**
     * Re-attaches the live Activity without disturbing a session that is already running
     * (invariant D-4: an Activity recreation must not open a second session).
     */
    fun attach(context: Context, connector: AutoConnectConnector) {
        appContext = context.applicationContext
        this.connector = connector
    }

    fun offer(trigger: AutoConnectTrigger, env: AutoConnectEnvironment): AutoConnectDecision {
        val decision = AutoConnectGate.evaluate(env, trigger)
        when (decision) {
            is AutoConnectDecision.Start -> {
                val request = startSession(decision.request.copy(sessionId = 0L), trigger)
                return if (request == null) {
                    AutoConnectDecision.Ignore(IgnoreReason.RUNNING)
                } else {
                    AutoConnectDecision.Start(request)
                }
            }
            is AutoConnectDecision.NeedsChoice -> {
                currentState = AutoConnectState.NeedsChoice(decision.reason, decision.candidates)
                publish()
                rememberProblem(decision.reason)
                recordDiagnostics(trigger, env, AutoConnectOutcome.NEEDS_CHOICE, AutoConnectFailure.IPHONE_NOT_CHOSEN)
            }
            is AutoConnectDecision.Blocked -> {
                currentState = AutoConnectState.Blocked(decision.reason)
                publish()
                recordDiagnostics(trigger, env, AutoConnectOutcome.BLOCKED, null)
            }
            is AutoConnectDecision.Ignore -> Unit
        }
        return decision
    }

    private fun startSession(template: AutoConnectRequest, trigger: AutoConnectTrigger): AutoConnectRequest? {
        if (active != null) return null
        val context = appContext ?: return null
        val budget = AutoConnectBudget(clock)
        budget.begin()
        if (budget.attemptPermit() == null) return null
        val id = nextId++
        val request = template.copy(sessionId = id, trigger = trigger)
        active = Active(id, trigger, request.mode, request.tier, budget)
        lastRequest = request
        currentState = AutoConnectState.Running(AutoConnectStage.PREPARING_NETWORK, 1, AutoConnectBudget.MAX_ATTEMPTS, 0L)
        publish()
        val started = connector?.start(request) ?: false
        if (!started) {
            currentState = AutoConnectState.Failed(AutoConnectFailure.UNKNOWN, 1, budget.elapsedMillis())
            active = null
            publish()
        }
        return request
    }

    fun onStage(sessionId: Long, stage: AutoConnectStage) {
        val session = active ?: return
        if (session.id != sessionId) return
        if (currentState.terminal) return
        session.stage = stage
        currentState = AutoConnectState.Running(stage, session.budget.attemptCount, AutoConnectBudget.MAX_ATTEMPTS,
            session.budget.elapsedMillis())
        publish()
    }

    fun onConnected(sessionId: Long, mode: com.shilapi.xcertplay.orchestration.WirelessHotspotMode) {
        val session = active ?: return
        if (session.id != sessionId || currentState.terminal) return
        val context = appContext
        if (context != null) AutoConnectPreferences.recordSuccess(context, mode, System.currentTimeMillis())
        currentState = AutoConnectState.Succeeded(mode)
        active = null
        publish()
        recordDiagnostics(session.trigger, mode, AutoConnectOutcome.SUCCEEDED, null, session)
    }

    fun onFailure(sessionId: Long, failure: AutoConnectFailure) {
        val session = active ?: return
        if (session.id != sessionId || currentState.terminal) return
        val budget = session.budget
        if (!failure.retryable || !budget.canRetry()) {
            currentState = AutoConnectState.Failed(failure, budget.displayedAttempts(), budget.elapsedMillis())
            active = null
            publish()
            recordDiagnostics(session.trigger, session.mode, AutoConnectOutcome.FAILED, failure, session)
        } else {
            currentState = AutoConnectState.Running(session.stage, budget.attemptCount,
                AutoConnectBudget.MAX_ATTEMPTS, budget.elapsedMillis())
            publish()
        }
    }

    /** The host asks before every restart; null means stop (G-01/ADR-003). */
    fun attemptPermit(sessionId: Long): Long? {
        val session = active ?: return null
        if (session.id != sessionId) return null
        return session.budget.attemptPermit()
    }

    fun retryFromUi(): AutoConnectDecision? {
        val previous = lastRequest ?: return null
        active = null
        return offer(AutoConnectTrigger.RETRY_BUTTON, environmentFor(previous))
    }

    fun cancel(reason: CancelReason) {
        val session = active ?: return
        session.budget.cancel(reason)
        connector?.cancelSession(session.id)
        active = null
        currentState = AutoConnectState.Cancelled(reason)
        publish()
        recordDiagnostics(session.trigger, session.mode, AutoConnectOutcome.CANCELLED, null, session)
    }

    fun state(): AutoConnectState = currentState

    fun activeSessionId(): Long? = active?.id

    fun addListener(listener: (AutoConnectState) -> Unit) {
        if (listener !in listeners) listeners.add(listener)
    }

    fun removeListener(listener: (AutoConnectState) -> Unit) {
        listeners.remove(listener)
    }

    /** G-08: the process singleton must look like a fresh install between test cases. */
    fun resetForTest() = resetState()

    private fun resetState() {
        active = null
        currentState = AutoConnectState.Idle
        nextId = 1L
        lastRequest = null
        listeners.clear()
    }

    private fun publish() {
        val state = currentState
        for (listener in listeners.toList()) listener(state)
    }

    private fun environmentFor(request: AutoConnectRequest): AutoConnectEnvironment {
        val context = appContext ?: return AutoConnectEnvironment(
            masterEnabled = true, openOnLaunch = true, connectOnPhoneBluetooth = true, autoStartOnBoot = true,
            wirelessMode = request.mode, preferredAddress = request.preferredAddress,
            preferredLastSuccessAt = null, nowElapsedMillis = clock(),
        )
        return AutoConnectEnvironment(
            masterEnabled = AutoConnectPreferences.masterEnabled(context),
            openOnLaunch = true,
            connectOnPhoneBluetooth = true,
            autoStartOnBoot = true,
            wirelessMode = request.mode,
            preferredAddress = com.shilapi.xcertplay.DiPlayPreferences.phoneAddress(context),
            preferredLastSuccessAt = AutoConnectPreferences.preferred(context)?.lastSuccessAt,
            bondedIphones = listOf(BondedIphone(request.preferredAddress)),
            nowElapsedMillis = clock(),
        )
    }

    private fun rememberProblem(reason: ChoiceReason) {
        val context = appContext ?: return
        val cause = when (reason) {
            ChoiceReason.NO_PREFERRED, ChoiceReason.MULTIPLE_CANDIDATES -> com.shilapi.xcertplay.orchestration.ConnectionFailureCause.NO_PAIRED_IPHONE
            ChoiceReason.PREFERRED_INVALID -> com.shilapi.xcertplay.orchestration.ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED
            ChoiceReason.PREFERRED_UNREACHABLE -> com.shilapi.xcertplay.orchestration.ConnectionFailureCause.IPHONE_NOT_CHOSEN
        }
        com.shilapi.xcertplay.LastConnectionProblem.follow(
            context,
            com.shilapi.xcertplay.orchestration.CarPlayStatus.Failed("automatic connection needs a choice", knownCause = cause),
        )
    }

    private fun recordDiagnostics(
        trigger: AutoConnectTrigger,
        env: AutoConnectEnvironment,
        outcome: AutoConnectOutcome,
        failure: AutoConnectFailure?,
        session: Active? = null,
    ) {
        val context = appContext ?: return
        AutoConnectDiagnostics.record(
            context,
            AutoConnectRecord(
                trigger = trigger,
                backend = session?.mode ?: env.wirelessMode,
                stage = session?.stage ?: AutoConnectStage.PREPARING_NETWORK,
                attempts = session?.budget?.displayedAttempts() ?: 1,
                elapsedMillis = session?.budget?.elapsedMillis() ?: 0L,
                outcome = outcome,
                failure = failure,
                tier = AutoConnectGate.tier(env),
                atMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun recordDiagnostics(
        trigger: AutoConnectTrigger,
        mode: com.shilapi.xcertplay.orchestration.WirelessHotspotMode,
        outcome: AutoConnectOutcome,
        failure: AutoConnectFailure?,
        session: Active? = null,
    ) {
        val context = appContext ?: return
        AutoConnectDiagnostics.record(
            context,
            AutoConnectRecord(
                trigger = trigger,
                backend = mode,
                stage = session?.stage ?: AutoConnectStage.PREPARING_NETWORK,
                attempts = session?.budget?.displayedAttempts() ?: 1,
                elapsedMillis = session?.budget?.elapsedMillis() ?: 0L,
                outcome = outcome,
                failure = failure,
                tier = session?.tier ?: AutoConnectTier.C,
                atMillis = System.currentTimeMillis(),
            ),
        )
    }
}

/** The process-local token that marks a launch as DiPlay's own automatic cold start. */
internal object AutoConnectLaunchToken {
    val token: String = java.util.UUID.randomUUID().toString()

    /** The automatic session handed to the CarPlay screen; only valid with [token]. */
    const val EXTRA_SESSION = "com.shihab.diplay.AUTO_CONNECT_SESSION"
    const val EXTRA_SESSION_TOKEN = "com.shihab.diplay.AUTO_CONNECT_SESSION_TOKEN"
}
