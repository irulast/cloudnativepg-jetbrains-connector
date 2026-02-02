package com.irulast.cloudnativepg.intellij.services

import com.irulast.cloudnativepg.intellij.kubernetes.KubernetesClientProvider
import com.irulast.cloudnativepg.intellij.models.ActiveConnection
import com.irulast.cloudnativepg.intellij.models.CnpgCluster
import com.irulast.cloudnativepg.intellij.models.ConnectionState
import com.irulast.cloudnativepg.intellij.settings.CnpgSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.LocalPortForward
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for managing port-forward connections to CloudNativePG clusters.
 */
@Service(Service.Level.APP)
class PortForwardService : Disposable {

    private val log = Logger.getInstance(PortForwardService::class.java)
    private val activeConnections = ConcurrentHashMap<String, ActiveConnection>()

    /**
     * Start a port-forward to a CloudNativePG cluster.
     *
     * @param context The Kubernetes context.
     * @param cluster The cluster to connect to.
     * @param useReplica Whether to connect to a read replica.
     * @return The connection state with local port.
     */
    suspend fun startPortForward(
        context: String,
        cluster: CnpgCluster,
        useReplica: Boolean = false
    ): ConnectionState.Connected = withContext(Dispatchers.IO) {
        val connectionKey = getConnectionKey(context, cluster)

        // Check if already connected
        activeConnections[connectionKey]?.let { existing ->
            if (existing.isAlive) {
                log.info("Already connected to ${cluster.displayName}")
                return@withContext ConnectionState.Connected(
                    localPort = existing.localPort,
                    cluster = cluster,
                    isReplica = existing.isReplica
                )
            } else {
                // Clean up dead connection
                existing.close()
                activeConnections.remove(connectionKey)
            }
        }

        log.info("Starting port-forward to ${cluster.displayName} (replica: $useReplica)")

        val client = KubernetesClientProvider.getInstance().getClient(context)

        // Find a ready pod for the service
        val podName = findReadyPod(client, cluster, useReplica)
            ?: throw PortForwardException("No ready pods available for ${cluster.displayName}")

        // Find an available local port
        val localPort = findAvailablePort()

        // Start port-forward to the pod
        val portForward = try {
            client.pods()
                .inNamespace(cluster.namespace)
                .withName(podName)
                .portForward(5432, localPort)
        } catch (e: Exception) {
            throw PortForwardException(
                "Failed to establish port-forward to ${cluster.displayName}: ${e.message}", e
            )
        }

        val connection = ActiveConnection(
            cluster = cluster,
            context = context,
            localPort = localPort,
            portForward = portForward,
            isReplica = useReplica
        )

        activeConnections[connectionKey] = connection
        log.info("Port-forward established: localhost:$localPort -> $podName:5432")

        ConnectionState.Connected(
            localPort = localPort,
            cluster = cluster,
            isReplica = useReplica
        )
    }

    /**
     * Stop a port-forward connection.
     *
     * @param context The Kubernetes context.
     * @param cluster The cluster to disconnect from.
     */
    fun stopPortForward(context: String, cluster: CnpgCluster) {
        val connectionKey = getConnectionKey(context, cluster)
        activeConnections.remove(connectionKey)?.let { connection ->
            log.info("Stopping port-forward to ${cluster.displayName}")
            connection.close()
        }
    }

    /**
     * Stop a port-forward by cluster key.
     */
    fun stopPortForward(cluster: CnpgCluster) {
        // Find and remove any connection for this cluster (any context)
        val toRemove = activeConnections.entries
            .filter { it.value.cluster.key == cluster.key }
            .map { it.key }

        toRemove.forEach { key ->
            activeConnections.remove(key)?.close()
        }
    }

    /**
     * Get all active connections.
     */
    fun getActiveConnections(): List<ActiveConnection> {
        // Clean up dead connections
        val deadKeys = activeConnections.entries
            .filter { !it.value.isAlive }
            .map { it.key }

        deadKeys.forEach { key ->
            activeConnections.remove(key)?.close()
        }

        return activeConnections.values.toList()
    }

    /**
     * Get the active connection for a cluster, if any.
     */
    fun getConnection(context: String, cluster: CnpgCluster): ActiveConnection? {
        val key = getConnectionKey(context, cluster)
        return activeConnections[key]?.takeIf { it.isAlive }
    }

    /**
     * Check if a cluster has an active connection.
     */
    fun isConnected(cluster: CnpgCluster): Boolean {
        return activeConnections.values.any {
            it.cluster.key == cluster.key && it.isAlive
        }
    }

    /**
     * Get the connection for a cluster if connected.
     */
    fun getConnectionForCluster(cluster: CnpgCluster): ActiveConnection? {
        return activeConnections.values.find {
            it.cluster.key == cluster.key && it.isAlive
        }
    }

    /**
     * Find a ready pod for the cluster.
     */
    private fun findReadyPod(
        client: KubernetesClient,
        cluster: CnpgCluster,
        useReplica: Boolean
    ): String? {
        val pods = client.pods()
            .inNamespace(cluster.namespace)
            .withLabel("cnpg.io/cluster", cluster.name)
            .list()
            .items

        // Find a ready pod based on role
        val targetRole = if (useReplica) "replica" else "primary"

        // First try to find a pod with the specific role
        val rolePod = pods.find { pod ->
            val role = pod.metadata?.labels?.get("cnpg.io/instanceRole") ?: pod.metadata?.labels?.get("role")
            val isReady = pod.status?.conditions?.any {
                it.type == "Ready" && it.status == "True"
            } == true
            isReady && role == targetRole
        }

        if (rolePod != null) {
            return rolePod.metadata.name
        }

        // If looking for primary, use the currentPrimary from cluster status
        if (!useReplica && cluster.primaryPod != null) {
            val primaryPod = pods.find { it.metadata.name == cluster.primaryPod }
            if (primaryPod != null) {
                return primaryPod.metadata.name
            }
        }

        // Fall back to any ready pod
        return pods.find { pod ->
            pod.status?.conditions?.any {
                it.type == "Ready" && it.status == "True"
            } == true
        }?.metadata?.name
    }

    /**
     * Find an available local port in the configured range.
     */
    private fun findAvailablePort(): Int {
        val settings = CnpgSettings.getInstance()
        val startPort = settings.portRangeStart
        val endPort = settings.portRangeEnd

        for (port in startPort..endPort) {
            try {
                ServerSocket(port).use {
                    return port
                }
            } catch (_: Exception) {
                // Port in use, try next
            }
        }

        // Fall back to any available port
        return ServerSocket(0).use { it.localPort }
    }

    /**
     * Get a unique key for a connection.
     */
    private fun getConnectionKey(context: String, cluster: CnpgCluster): String {
        return "$context/${cluster.key}"
    }

    override fun dispose() {
        log.info("Disposing PortForwardService, closing ${activeConnections.size} connections")
        activeConnections.values.forEach { connection ->
            try {
                connection.close()
            } catch (e: Exception) {
                log.warn("Error closing port-forward", e)
            }
        }
        activeConnections.clear()
    }

    companion object {
        @JvmStatic
        fun getInstance(): PortForwardService = service()
    }
}

/**
 * Exception for port-forward errors.
 */
class PortForwardException(message: String, cause: Throwable? = null) : Exception(message, cause)
