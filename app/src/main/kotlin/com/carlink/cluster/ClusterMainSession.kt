package com.carlink.cluster

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.carlink.logging.Logger
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import com.carlink.logging.logNavi
import com.carlink.logging.logWarn
import com.carlink.navigation.NavigationState
import com.carlink.navigation.NavigationStateManager
import com.carlink.navigation.TripBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * GM AAOS cluster session — relays Trip data via NavigationManager.updateTrip().
 *
 * This is the ACTIVE session returned by [CarlinkClusterService]. It works on GM AAOS
 * because GM has an internal cluster manager (OnStarTurnByTurnManager) that consumes
 * NavigationManager data and renders turn-by-turn on the instrument cluster. The
 * [RelayScreen] is never visible — GM's system ignores it.
 *
 * **GM-specific**: On non-GM AAOS platforms that render Screen.onGetTemplate() directly,
 * this session would show static text instead of navigation data. See [CarlinkClusterSession]
 * for the standard Car App Library approach needed on those platforms.
 *
 * Primary/secondary multiplexing handles Templates Host creating multiple sessions:
 * - AAOS emulator: creates DISPLAY_TYPE_MAIN + DISPLAY_TYPE_CLUSTER (two sessions)
 * - GM AAOS: creates only DISPLAY_TYPE_MAIN (one session)
 * Only the first (primary) owns NavigationManager. Subsequent sessions are passive.
 */
class ClusterMainSession : Session() {
    private var navigationManager: NavigationManager? = null
    private var scope: CoroutineScope? = null
    private var isNavigating = false
    private var isPrimary = false

    /** Only call navigationEnded() after we've seen at least one active state transition to idle.
     *  Without this, the initial idle state from NavigationStateManager kills the binding chain
     *  before Templates Host can create the cluster session (displayType=1). */
    private var hasSeenActiveNav = false

    /** Pending arrival timeout — fires navigationEnded() if adapter doesn't send NaviStatus=0
     *  after a terminal maneuver (arrived, endOfNavigation, endOfDirections). */
    private var arrivalTimeoutJob: Job? = null

    companion object {
        /** Terminal CPManeuverType values that indicate navigation is complete. */
        private val TERMINAL_MANEUVER_TYPES = intArrayOf(
            10, // endOfNavigation
            12, // arrived
            24, // arrivedLeft
            25, // arrivedRight
            27, // endOfDirections
        )

        /** Grace period for adapter to send NaviStatus=0 after terminal maneuver. */
        private const val ARRIVAL_TIMEOUT_MS = 10_000L

        /** First live session wins; cleared on destroy so a fresh binding chain can take over. */
        @Volatile
        private var primarySession: ClusterMainSession? = null
    }

    override fun onCreateScreen(intent: Intent): Screen {
        ClusterBindingState.sessionAlive = true

        // Claim primary role if no other session holds it.
        isPrimary = primarySession == null
        if (isPrimary) {
            primarySession = this
            logInfo("[CLUSTER_MAIN] Primary session created — owns NavigationManager", tag = Logger.Tags.CLUSTER)
        } else {
            logInfo("[CLUSTER_MAIN] Secondary session created — passive (no NavigationManager calls)", tag = Logger.Tags.CLUSTER)
            return RelayScreen(carContext)
        }

        // --- Everything below runs only for the primary session ---

        // Get NavigationManager — needed for navigationStarted() which triggers cluster creation
        try {
            navigationManager = carContext.getCarService(NavigationManager::class.java)
            logInfo("[CLUSTER_MAIN] NavigationManager obtained", tag = Logger.Tags.CLUSTER)
        } catch (e: Exception) {
            logError(
                "[CLUSTER_MAIN] Failed to get NavigationManager: ${e.message}",
                tag = Logger.Tags.CLUSTER,
                throwable = e,
            )
        }

        // Set NavigationManagerCallback BEFORE calling navigationStarted() — Templates Host
        // requires the callback to be set first, otherwise navigationStarted() throws.
        navigationManager?.setNavigationManagerCallback(
            object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    logInfo("[CLUSTER_MAIN] onStopNavigation callback", tag = Logger.Tags.CLUSTER)
                    isNavigating = false
                }

                override fun onAutoDriveEnabled() {
                    logNavi { "[CLUSTER_MAIN] Auto drive enabled" }
                }
            },
        )

        // Call navigationStarted() IMMEDIATELY — this is the critical trigger that causes
        // Templates Host to create ClusterTurnCardActivity on the cluster display.
        // Without this, Templates Host never creates the cluster display.
        try {
            navigationManager?.navigationStarted()
            isNavigating = true
            logInfo("[CLUSTER_MAIN] navigationStarted() called", tag = Logger.Tags.CLUSTER)
        } catch (e: Exception) {
            logWarn("[CLUSTER_MAIN] navigationStarted() failed: ${e.message}", tag = Logger.Tags.CLUSTER)
        }

        // Observe NavigationStateManager to relay Trip updates
        val sessionScope = CoroutineScope(Dispatchers.Main)
        scope = sessionScope

        sessionScope.launch {
            collectNavigationState()
        }

        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    if (isPrimary) {
                        primarySession = null
                        logInfo(
                            "[CLUSTER_MAIN] Primary session destroyed — releasing NavigationManager ownership",
                            tag = Logger.Tags.CLUSTER,
                        )
                        arrivalTimeoutJob?.cancel()
                        arrivalTimeoutJob = null
                        if (isNavigating) {
                            try {
                                navigationManager?.navigationEnded()
                                logNavi { "[CLUSTER_MAIN] navigationEnded() called on destroy" }
                            } catch (e: Exception) {
                                logError(
                                    "[CLUSTER_MAIN] navigationEnded() failed on destroy: ${e.message}",
                                    tag = Logger.Tags.CLUSTER,
                                    throwable = e,
                                )
                            }
                            isNavigating = false
                        }
                        scope?.cancel()
                        scope = null
                        navigationManager = null
                    } else {
                        logInfo("[CLUSTER_MAIN] Secondary session destroyed", tag = Logger.Tags.CLUSTER)
                    }
                    ClusterBindingState.sessionAlive = false
                }
            },
        )

        return RelayScreen(carContext)
    }

    /**
     * Collect navigation state with 200ms debounce.
     */
    private suspend fun collectNavigationState() {
        var debounceJob: Job? = null

        NavigationStateManager.state.collectLatest { state ->
            debounceJob?.cancel()

            debounceJob =
                scope?.launch {
                    delay(200)
                    processStateUpdate(state)
                }
        }
    }

    private fun processStateUpdate(state: NavigationState) {
        val navManager = navigationManager
        if (navManager == null) {
            logWarn("[CLUSTER_MAIN] NavigationManager is null — cannot relay", tag = Logger.Tags.CLUSTER)
            return
        }

        if (state.isActive) {
            hasSeenActiveNav = true

            // Re-start navigation if it was ended by a previous flush
            if (!isNavigating) {
                logInfo("[CLUSTER_MAIN] navigationStarted() (re-start)", tag = Logger.Tags.CLUSTER)
                try {
                    navManager.navigationStarted()
                    isNavigating = true
                } catch (e: Exception) {
                    logError(
                        "[CLUSTER_MAIN] navigationStarted() failed: ${e.message}",
                        tag = Logger.Tags.CLUSTER,
                        throwable = e,
                    )
                    return
                }
            }

            try {
                val trip = TripBuilder.buildTrip(state, carContext)
                navManager.updateTrip(trip)
                logNavi {
                    "[CLUSTER_MAIN] Trip relayed: maneuver=${state.maneuverType}, " +
                        "dist=${state.remainDistance}m, road=${state.roadName}" +
                        if (state.hasNextStep) ", nextManeuver=${state.nextManeuverType}, nextRoad=${state.nextRoadName}" else ""
                }
            } catch (e: Exception) {
                logError(
                    "[CLUSTER_MAIN] updateTrip() failed: ${e.message}",
                    tag = Logger.Tags.CLUSTER,
                    throwable = e,
                )
            }

            // Arrival timeout: if maneuver is a terminal type (arrived, endOfNavigation, etc.)
            // start a grace period. If the adapter doesn't send NaviStatus=0 within the window,
            // end navigation ourselves. Catches firmware gap where arrival is sent without flush.
            if (state.maneuverType in TERMINAL_MANEUVER_TYPES) {
                if (arrivalTimeoutJob?.isActive != true) {
                    logInfo(
                        "[CLUSTER_MAIN] Terminal maneuver (cpType=${state.maneuverType}) — " +
                            "starting ${ARRIVAL_TIMEOUT_MS / 1000}s arrival timeout",
                        tag = Logger.Tags.CLUSTER,
                    )
                    arrivalTimeoutJob = scope?.launch {
                        delay(ARRIVAL_TIMEOUT_MS)
                        if (isNavigating) {
                            logInfo(
                                "[CLUSTER_MAIN] Arrival timeout — adapter did not send flush, ending navigation",
                                tag = Logger.Tags.CLUSTER,
                            )
                            try {
                                navManager.navigationEnded()
                            } catch (e: Exception) {
                                logError(
                                    "[CLUSTER_MAIN] navigationEnded() failed (arrival timeout): ${e.message}",
                                    tag = Logger.Tags.CLUSTER,
                                    throwable = e,
                                )
                            }
                            isNavigating = false
                        }
                    }
                }
            } else {
                // Non-terminal maneuver — cancel any pending arrival timeout
                arrivalTimeoutJob?.cancel()
                arrivalTimeoutJob = null
            }
        } else if (state.isIdle && isNavigating && hasSeenActiveNav) {
            // Only end navigation if we previously saw active nav data.
            // The initial idle state must NOT kill the binding chain.
            arrivalTimeoutJob?.cancel()
            arrivalTimeoutJob = null
            logInfo("[CLUSTER_MAIN] navigationEnded() (flush signal)", tag = Logger.Tags.CLUSTER)
            try {
                navManager.navigationEnded()
            } catch (e: Exception) {
                logError(
                    "[CLUSTER_MAIN] navigationEnded() failed: ${e.message}",
                    tag = Logger.Tags.CLUSTER,
                    throwable = e,
                )
            }
            isNavigating = false
        }
    }

    /**
     * Relay screen — shows a brief identifying message while Templates Host binds
     * the cluster session. Visible for ~1s before MainActivity returns to front.
     */
    private class RelayScreen(
        carContext: CarContext,
    ) : Screen(carContext) {
        override fun onGetTemplate(): Template =
            NavigationTemplate
                .Builder()
                .setNavigationInfo(
                    MessageInfo
                        .Builder("Carlink — Cluster Navigation Service")
                        .setText(
                            "Main app should appear momentarily. If this screen persists, return to the app launcher and reopen Carlink.",
                        ).build(),
                ).setActionStrip(
                    ActionStrip
                        .Builder()
                        .addAction(Action.APP_ICON)
                        .build(),
                ).build()
    }
}
