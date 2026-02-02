package com.irulast.cloudnativepg.intellij.services

import com.irulast.cloudnativepg.intellij.models.CnpgCluster
import com.irulast.cloudnativepg.intellij.settings.CnpgSettings
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.readAction
import kotlinx.coroutines.*

/**
 * Manages the lifecycle of CloudNativePG data source connections.
 *
 * This service:
 * - Reconnects managed data sources on startup
 * - Monitors for removed data sources and cleans up port-forwards
 * - Handles credential refresh when needed
 *
 * State is derived from data source comments rather than stored separately.
 */
@Service(Service.Level.PROJECT)
class ManagedConnectionService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(ManagedConnectionService::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Track data sources we know about to detect removals
    // Maps data source UUID -> ClusterInfo for cleanup
    private val knownDataSources = mutableMapOf<String, ClusterInfo>()

    /**
     * Initialize the service - called after project is opened.
     */
    fun initialize() {
        log.info("ManagedConnectionService initializing for project: ${project.name}")

        try {
            val settings = CnpgSettings.getInstance()

            if (settings.autoReconnectOnStartup) {
                // IMMEDIATELY disable auto-sync to prevent Database Tools from trying to connect
                // with stale URLs before we re-establish port-forwards
                disableAutoSyncOnManagedDataSources()

                log.info("Auto-reconnect enabled, scheduling reconnection in 2 seconds...")
                scope.launch {
                    delay(2000) // Give IDE time to fully initialize
                    try {
                        reconnectManagedDataSources()
                    } catch (e: Exception) {
                        log.error("Error during reconnectManagedDataSources", e)
                    }
                }
            } else {
                log.info("Auto-reconnect on startup is disabled")
            }

            // Start monitoring for data source changes
            startDataSourceMonitoring()
        } catch (e: Exception) {
            log.error("Error during ManagedConnectionService initialization", e)
        }
    }

    /**
     * Immediately disable auto-sync on all managed data sources.
     * This should be called early on startup to prevent Database Tools from
     * trying to connect with stale URLs before we re-establish port-forwards.
     */
    fun disableAutoSyncOnManagedDataSources() {
        log.info("Disabling auto-sync on managed data sources...")

        // This runs synchronously on the calling thread (should be EDT from tool window init)
        val dataSourceManager = LocalDataSourceManager.getInstance(project)
        var count = 0

        dataSourceManager.dataSources.forEach { dataSource ->
            if (parseClusterInfo(dataSource) != null && dataSource.isAutoSynchronize) {
                dataSource.isAutoSynchronize = false
                count++
                log.info("Disabled auto-sync for: ${dataSource.name}")
            }
        }

        log.info("Disabled auto-sync on $count managed data source(s)")
    }

    /**
     * Find and reconnect all CloudNativePG-managed data sources.
     */
    suspend fun reconnectManagedDataSources() {
        log.info("Scanning for managed CloudNativePG data sources to reconnect...")

        // Access data sources via read action for thread safety
        val managedDataSources = readAction { findManagedDataSources() }

        if (managedDataSources.isEmpty()) {
            log.info("No managed CloudNativePG data sources found")
            return
        }

        log.info("Found ${managedDataSources.size} managed data source(s) to reconnect")

        for ((dataSource, clusterInfo) in managedDataSources) {
            try {
                reconnectDataSource(dataSource, clusterInfo)
                knownDataSources[dataSource.uniqueId] = clusterInfo
            } catch (e: Exception) {
                log.warn("Failed to reconnect data source: ${dataSource.name}", e)
            }
        }
    }

    /**
     * Find all data sources that were created by this plugin.
     */
    private fun findManagedDataSources(): List<Pair<LocalDataSource, ClusterInfo>> {
        val dataSourceManager = LocalDataSourceManager.getInstance(project)

        return dataSourceManager.dataSources.mapNotNull { dataSource ->
            parseClusterInfo(dataSource)?.let { info ->
                dataSource to info
            }
        }
    }

    /**
     * Parse cluster information from a data source's comment.
     * Returns null if this data source wasn't created by the plugin.
     */
    private fun parseClusterInfo(dataSource: LocalDataSource): ClusterInfo? {
        val comment = dataSource.comment ?: return null

        // Our comments look like:
        // CloudNativePG: namespace/name
        // Context: context
        // Type: Primary|Read Replica
        // PostgreSQL: version

        if (!comment.startsWith("CloudNativePG:")) {
            return null
        }

        try {
            val lines = comment.lines()

            // Parse the cluster key from first line (format: namespace/name)
            val clusterKeyLine = lines.firstOrNull { it.startsWith("CloudNativePG:") }
                ?: return null
            val clusterKey = clusterKeyLine.substringAfter("CloudNativePG:").trim()
            val keyParts = clusterKey.split("/")
            if (keyParts.size != 2) return null

            val (namespace, name) = keyParts

            // Parse context from second line
            val contextLine = lines.firstOrNull { it.startsWith("Context:") }
                ?: return null
            val context = contextLine.substringAfter("Context:").trim()

            // Parse type
            val typeLine = lines.firstOrNull { it.startsWith("Type:") }
            val isReplica = typeLine?.contains("Read Replica") == true

            // Parse database from URL
            val database = extractDatabaseFromUrl(dataSource.url)

            return ClusterInfo(
                context = context,
                namespace = namespace,
                name = name,
                isReplica = isReplica,
                database = database
            )
        } catch (e: Exception) {
            log.debug("Failed to parse cluster info from comment: $comment", e)
            return null
        }
    }

    /**
     * Extract database name from JDBC URL.
     */
    private fun extractDatabaseFromUrl(url: String?): String {
        if (url == null) return "app"

        // URL format: jdbc:postgresql://localhost:port/database
        return try {
            val path = url.substringAfter("://").substringAfter("/")
            path.substringBefore("?").ifEmpty { "app" }
        } catch (e: Exception) {
            "app"
        }
    }

    /**
     * Reconnect a single data source by re-establishing port-forward and refreshing credentials.
     */
    private suspend fun reconnectDataSource(dataSource: LocalDataSource, info: ClusterInfo) {
        log.info("Reconnecting data source: ${dataSource.name} (${info.context}/${info.namespace}/${info.name})")

        withContext(Dispatchers.IO) {
            try {
                val cnpgService = CnpgService.getInstance()
                val portForwardService = PortForwardService.getInstance()

                // Look up the actual cluster from Kubernetes to get all proper fields
                val clusters = cnpgService.listClusters(info.context, info.namespace)
                val cluster = clusters.find { it.name == info.name && it.namespace == info.namespace }

                if (cluster == null) {
                    log.warn("Cluster ${info.namespace}/${info.name} not found in Kubernetes - it may have been deleted")
                    return@withContext
                }

                // Always get fresh credentials from Kubernetes
                log.info("Fetching fresh credentials for ${cluster.displayName}")
                val credentials = cnpgService.getCredentials(cluster, info.context)
                log.info("Got credentials for user: ${credentials.username}, database: ${credentials.database}")

                // Check if already connected - if so, just refresh credentials
                val existingConnection = portForwardService.getConnectionForCluster(cluster)
                val localPort: Int

                if (existingConnection != null && existingConnection.isAlive) {
                    log.info("Data source ${dataSource.name} already has active port-forward on port ${existingConnection.localPort}")
                    localPort = existingConnection.localPort
                } else {
                    // Establish port-forward
                    val connectedState = portForwardService.startPortForward(
                        context = info.context,
                        cluster = cluster,
                        useReplica = info.isReplica
                    )
                    localPort = connectedState.localPort
                }

                // Always update the data source with fresh credentials
                DatabaseToolsService.getInstance(project).updateDataSourceConnection(
                    dataSource = dataSource,
                    credentials = credentials,
                    localPort = localPort
                )

                // Notify ConnectionManager so the UI updates
                ConnectionManager.getInstance().notifyConnected(
                    cluster = cluster,
                    localPort = localPort,
                    isReplica = info.isReplica
                )

                log.info("Successfully reconnected data source: ${dataSource.name} on port $localPort")

            } catch (e: Exception) {
                log.warn("Failed to reconnect ${dataSource.name}: ${e.message}", e)
                // Don't throw - we want to continue with other data sources
            }
        }
    }

    /**
     * Start monitoring for data source changes to detect removals.
     */
    private fun startDataSourceMonitoring() {
        log.debug("Starting data source monitoring")
        // Periodically check for removed data sources
        scope.launch {
            while (isActive) {
                delay(10_000) // Check every 10 seconds
                try {
                    checkForRemovedDataSources()
                } catch (e: Exception) {
                    log.debug("Error checking for removed data sources: ${e.message}")
                }
            }
        }
    }

    /**
     * Check if any managed data sources have been removed by the user.
     * When a data source is removed, disconnect the corresponding port-forward.
     */
    private suspend fun checkForRemovedDataSources() {
        // Access data source manager via read action for thread safety
        val currentDataSources = try {
            readAction {
                findManagedDataSources().associate { it.first.uniqueId to it.second }
            }
        } catch (e: Exception) {
            log.warn("Error accessing data sources: ${e.message}")
            emptyMap()
        }

        val currentIds = currentDataSources.keys
        val removedIds = knownDataSources.keys - currentIds

        for (removedId in removedIds) {
            val clusterInfo = knownDataSources[removedId]
            if (clusterInfo != null) {
                log.info("Detected removed data source: $removedId (${clusterInfo.namespace}/${clusterInfo.name})")
                disconnectRemovedDataSource(clusterInfo)
            }
            knownDataSources.remove(removedId)
        }

        // Update known map with any new ones
        knownDataSources.putAll(currentDataSources)
    }

    /**
     * Disconnect port-forward for a removed data source.
     */
    private suspend fun disconnectRemovedDataSource(info: ClusterInfo) {
        withContext(Dispatchers.IO) {
            try {
                val portForwardService = PortForwardService.getInstance()

                // Find connection by matching cluster info
                val connection = portForwardService.getActiveConnections().find { conn ->
                    conn.cluster.namespace == info.namespace &&
                    conn.cluster.name == info.name &&
                    conn.context == info.context &&
                    conn.isReplica == info.isReplica
                }

                if (connection != null) {
                    log.info("Disconnecting port-forward for removed data source: ${info.namespace}/${info.name}")
                    portForwardService.stopPortForward(connection.cluster)
                    ConnectionManager.getInstance().notifyDisconnected(connection.cluster)

                    // Show notification
                    showNotification(
                        "Port-Forward Disconnected",
                        "Connection to ${info.namespace}/${info.name} was closed because the data source was removed.",
                        NotificationType.INFORMATION
                    )
                } else {
                    log.debug("No active port-forward found for removed data source: ${info.namespace}/${info.name}")
                }
            } catch (e: Exception) {
                log.warn("Error disconnecting port-forward for removed data source: ${e.message}", e)
            }
        }
    }

    /**
     * Show a notification to the user.
     */
    private fun showNotification(title: String, content: String, type: NotificationType) {
        if (!CnpgSettings.getInstance().showNotifications) return

        NotificationGroupManager.getInstance()
            .getNotificationGroup("CloudNativePG Connector")
            .createNotification(title, content, type)
            .notify(project)
    }

    /**
     * Manually trigger reconnection of a specific data source.
     */
    suspend fun reconnectDataSource(dataSource: LocalDataSource) {
        val info = parseClusterInfo(dataSource)
        if (info == null) {
            log.warn("Cannot reconnect ${dataSource.name} - not a managed CloudNativePG data source")
            return
        }

        reconnectDataSource(dataSource, info)
    }

    /**
     * Check if a data source is managed by this plugin.
     */
    fun isManagedDataSource(dataSource: LocalDataSource): Boolean {
        return parseClusterInfo(dataSource) != null
    }

    /**
     * Register a newly created data source for removal monitoring.
     * Called when a new connection is established via the plugin.
     */
    fun registerDataSource(dataSource: LocalDataSource) {
        val info = parseClusterInfo(dataSource)
        if (info != null) {
            knownDataSources[dataSource.uniqueId] = info
            log.info("Registered data source for monitoring: ${dataSource.name} (${info.namespace}/${info.name})")
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    /**
     * Information about a cluster parsed from a data source.
     */
    data class ClusterInfo(
        val context: String,
        val namespace: String,
        val name: String,
        val isReplica: Boolean,
        val database: String
    )

    companion object {
        @JvmStatic
        fun getInstance(project: Project): ManagedConnectionService = project.service()
    }
}