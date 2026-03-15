package com.carlink.cluster

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import com.carlink.logging.Logger
import com.carlink.logging.logInfo

/**
 * Headless CarAppService for cluster navigation display only.
 *
 * Currently always returns [ClusterMainSession] (GM AAOS path — NavigationManager relay).
 * For non-GM AAOS platforms, [CarlinkClusterSession] (direct Screen rendering) should be
 * returned instead. TODO: Add platform-aware switch here.
 *
 * MainActivity remains the sole LAUNCHER and owns all USB/video/audio pipelines.
 * This service does NOT initialize CarlinkManager, video, audio, or USB.
 */
class CarlinkClusterService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    @Suppress("DEPRECATION")
    override fun onCreateSession(): Session {
        logInfo("[CLUSTER_SVC] Creating session (no SessionInfo — fallback)", tag = Logger.Tags.CLUSTER)
        return ClusterMainSession()
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        logInfo("[CLUSTER_SVC] Creating session: displayType=${sessionInfo.displayType}", tag = Logger.Tags.CLUSTER)
        return ClusterMainSession()
    }
}
