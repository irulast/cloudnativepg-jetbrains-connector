package com.irulast.cloudnativepg.intellij.services

import com.irulast.cloudnativepg.intellij.kubernetes.CnpgClusterDiscovery
import com.irulast.cloudnativepg.intellij.kubernetes.KubernetesClientProvider
import com.irulast.cloudnativepg.intellij.models.CnpgCluster
import com.irulast.cloudnativepg.intellij.models.ConnectionState
import com.irulast.cloudnativepg.intellij.models.DatabaseCredentials
import com.irulast.cloudnativepg.intellij.settings.CnpgSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Main orchestration service for CloudNativePG operations.
 */
@Service(Service.Level.APP)
class CnpgService : Disposable {

    private val log = Logger.getInstance(CnpgService::class.java)

    private val clientProvider: KubernetesClientProvider
        get() = KubernetesClientProvider.getInstance()

    private val portForwardService: PortForwardService
        get() = PortForwardService.getInstance()

    private val connectionManager: ConnectionManager
        get() = ConnectionManager.getInstance()

    /**
     * List all CloudNativePG clusters for the specified context.
     *
     * @param context The Kubernetes context, or null for current context.
     * @param namespace Namespace to filter by, or null for all namespaces.
     * @return List of discovered clusters.
     */
    suspend fun listClusters(
        context: String? = null,
        namespace: String? = null
    ): List<CnpgCluster> = withContext(Dispatchers.IO) {
        val effectiveContext = context ?: clientProvider.getCurrentContext()
        log.info("Listing clusters in context: $effectiveContext, namespace: ${namespace ?: "all"}")

        val client = clientProvider.getClient(effectiveContext)
        val discovery = CnpgClusterDiscovery(client)
        discovery.listClusters(namespace)
    }

    /**
     * Get credentials for a cluster.
     *
     * @param cluster The cluster to get credentials for.
     * @param context The Kubernetes context.
     * @return Database credentials.
     */
    suspend fun getCredentials(
        cluster: CnpgCluster,
        context: String? = null
    ): DatabaseCredentials = withContext(Dispatchers.IO) {
        val effectiveContext = context ?: clientProvider.getCurrentContext()
        val client = clientProvider.getClient(effectiveContext)
        val discovery = CnpgClusterDiscovery(client)
        discovery.getCredentials(cluster)
    }

    /**
     * Connect to a CloudNativePG cluster.
     *
     * @param project The current project.
     * @param context The Kubernetes context.
     * @param cluster The cluster to connect to.
     * @param useReplica Whether to connect to a read replica.
     * @return The connection state.
     */
    suspend fun connect(
        project: Project,
        context: String,
        cluster: CnpgCluster,
        useReplica: Boolean = false
    ): ConnectionState = withContext(Dispatchers.IO) {
        log.info("Connecting to ${cluster.displayName} (replica: $useReplica)")
        connectionManager.notifyConnecting(cluster)

        try {
            // Establish port-forward
            val connectedState = portForwardService.startPortForward(context, cluster, useReplica)

            // Get credentials and add to Database Tools if enabled
            val settings = CnpgSettings.getInstance()
            if (settings.autoAddToDbTools) {
                try {
                    val credentials = getCredentials(cluster, context)
                    DatabaseToolsService.getInstance(project).addDataSource(
                        context = context,
                        cluster = cluster,
                        credentials = credentials,
                        localPort = connectedState.localPort,
                        isReplica = useReplica
                    )
                } catch (e: Exception) {
                    log.warn("Failed to add data source to Database Tools", e)
                    // Don't fail the connection for this
                }
            }

            connectionManager.notifyConnected(cluster, connectedState.localPort, useReplica)
            connectedState
        } catch (e: Exception) {
            log.error("Failed to connect to ${cluster.displayName}", e)
            val error = e.message ?: "Unknown error"
            connectionManager.notifyFailed(cluster, error)
            ConnectionState.Failed(cluster, error)
        }
    }

    /**
     * Disconnect from a CloudNativePG cluster.
     *
     * @param project The current project.
     * @param context The Kubernetes context.
     * @param cluster The cluster to disconnect from.
     */
    fun disconnect(project: Project?, context: String, cluster: CnpgCluster) {
        log.info("Disconnecting from ${cluster.displayName}")

        portForwardService.stopPortForward(context, cluster)

        // Optionally remove data source
        val settings = CnpgSettings.getInstance()
        if (settings.autoRemoveOnDisconnect && project != null) {
            try {
                DatabaseToolsService.getInstance(project).removeDataSource(cluster)
            } catch (e: Exception) {
                log.warn("Failed to remove data source from Database Tools", e)
            }
        }

        connectionManager.notifyDisconnected(cluster)
    }

    /**
     * Get available Kubernetes contexts.
     */
    fun getContexts(): List<String> {
        return clientProvider.listContexts()
    }

    /**
     * Get the current Kubernetes context.
     */
    fun getCurrentContext(): String {
        return clientProvider.getCurrentContext()
    }

    /**
     * Check if a cluster is connected.
     */
    fun isConnected(cluster: CnpgCluster): Boolean {
        return portForwardService.isConnected(cluster)
    }

    /**
     * Refresh Kubernetes configuration.
     */
    fun refreshConfig() {
        clientProvider.refresh()
    }

    override fun dispose() {
        // Cleanup handled by PortForwardService
    }

    companion object {
        @JvmStatic
        fun getInstance(): CnpgService = service()
    }
}
