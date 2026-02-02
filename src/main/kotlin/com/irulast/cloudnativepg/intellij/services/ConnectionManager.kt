package com.irulast.cloudnativepg.intellij.services

import com.irulast.cloudnativepg.intellij.models.CnpgCluster
import com.irulast.cloudnativepg.intellij.models.ConnectionState
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages connection lifecycle and notifies listeners of state changes.
 */
@Service(Service.Level.APP)
class ConnectionManager : Disposable {

    private val log = Logger.getInstance(ConnectionManager::class.java)
    private val listeners = CopyOnWriteArrayList<ConnectionListener>()

    /**
     * Listener interface for connection state changes.
     */
    fun interface ConnectionListener {
        fun onConnectionChanged(cluster: CnpgCluster, state: ConnectionState)
    }

    /**
     * Add a connection listener.
     */
    fun addListener(listener: ConnectionListener) {
        listeners.add(listener)
    }

    /**
     * Remove a connection listener.
     */
    fun removeListener(listener: ConnectionListener) {
        listeners.remove(listener)
    }

    /**
     * Notify listeners of a connection state change.
     */
    fun notifyConnectionChanged(cluster: CnpgCluster, state: ConnectionState) {
        log.info("Connection state changed for ${cluster.displayName}: $state (${listeners.size} listeners)")
        listeners.forEach { listener ->
            try {
                listener.onConnectionChanged(cluster, state)
            } catch (e: Exception) {
                log.warn("Error notifying connection listener", e)
            }
        }
    }

    /**
     * Notify listeners that a connection is connecting.
     */
    fun notifyConnecting(cluster: CnpgCluster) {
        notifyConnectionChanged(cluster, ConnectionState.Connecting(cluster))
    }

    /**
     * Notify listeners that a connection was established.
     */
    fun notifyConnected(cluster: CnpgCluster, localPort: Int, isReplica: Boolean) {
        notifyConnectionChanged(
            cluster,
            ConnectionState.Connected(localPort, cluster, isReplica)
        )
    }

    /**
     * Notify listeners that a connection was disconnected.
     */
    fun notifyDisconnected(cluster: CnpgCluster) {
        notifyConnectionChanged(cluster, ConnectionState.Disconnected(cluster))
    }

    /**
     * Notify listeners that a connection failed.
     */
    fun notifyFailed(cluster: CnpgCluster, error: String) {
        notifyConnectionChanged(cluster, ConnectionState.Failed(cluster, error))
    }

    override fun dispose() {
        listeners.clear()
    }

    companion object {
        @JvmStatic
        fun getInstance(): ConnectionManager = service()
    }
}
