package com.irulast.cloudnativepg.intellij.models

import io.fabric8.kubernetes.client.LocalPortForward
import java.time.Instant

/**
 * Represents the connection state for a CloudNativePG cluster.
 */
sealed class ConnectionState {
    /**
     * Get the cluster associated with this state.
     */
    abstract val cluster: CnpgCluster

    /**
     * The cluster has an active port-forward connection.
     */
    data class Connected(
        val localPort: Int,
        override val cluster: CnpgCluster,
        val isReplica: Boolean
    ) : ConnectionState()

    /**
     * The cluster is not connected.
     */
    data class Disconnected(
        override val cluster: CnpgCluster
    ) : ConnectionState()

    /**
     * The cluster is in the process of connecting.
     */
    data class Connecting(
        override val cluster: CnpgCluster
    ) : ConnectionState()

    /**
     * Connection to the cluster failed.
     */
    data class Failed(
        override val cluster: CnpgCluster,
        val error: String
    ) : ConnectionState()
}

/**
 * Represents an active port-forward connection.
 */
data class ActiveConnection(
    val cluster: CnpgCluster,
    val context: String,
    val localPort: Int,
    val portForward: LocalPortForward,
    val isReplica: Boolean,
    val connectedAt: Instant = Instant.now()
) {
    /**
     * Unique key for this connection.
     */
    val key: String
        get() = "${context}/${cluster.key}"

    /**
     * Check if the port-forward is still alive.
     */
    val isAlive: Boolean
        get() = portForward.isAlive

    /**
     * Close the port-forward connection.
     */
    fun close() {
        try {
            portForward.close()
        } catch (_: Exception) {
            // Ignore close errors
        }
    }
}
