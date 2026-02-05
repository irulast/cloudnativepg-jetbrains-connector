package com.irulast.cloudnativepg.intellij.services

import com.irulast.cloudnativepg.intellij.models.CnpgCluster
import com.irulast.cloudnativepg.intellij.models.DatabaseCredentials
import com.irulast.cloudnativepg.intellij.settings.CnpgSettings
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DatabaseCredentials as DbCredentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.SchemaControl
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture

/**
 * Service for integrating with JetBrains Database Tools.
 */
@Service(Service.Level.PROJECT)
class DatabaseToolsService(private val project: Project) {

    private val log = Logger.getInstance(DatabaseToolsService::class.java)

    /**
     * Add a data source for a connected CloudNativePG cluster.
     * This method handles EDT requirements internally.
     *
     * @param context Kubernetes context name.
     * @param cluster The CloudNativePG cluster.
     * @param credentials Database credentials.
     * @param localPort Local port-forward port.
     * @param isReplica Whether this is a read replica connection.
     * @return The created or updated LocalDataSource.
     */
    fun addDataSource(
        context: String,
        cluster: CnpgCluster,
        credentials: DatabaseCredentials,
        localPort: Int,
        isReplica: Boolean
    ): LocalDataSource {
        log.info("Adding data source for ${cluster.displayName} on port $localPort")

        val dataSourceName = generateDataSourceName(context, cluster, isReplica)

        // Data source operations must run on EDT
        // Use ModalityState.any() to ensure execution even during modal dialogs
        val future = CompletableFuture<LocalDataSource>()

        ApplicationManager.getApplication().invokeLater({
            try {
                val dataSourceManager = LocalDataSourceManager.getInstance(project)

                // Check if data source already exists
                val existing = dataSourceManager.dataSources.find { it.name == dataSourceName }
                if (existing != null) {
                    log.info("Updating existing data source: $dataSourceName")
                    future.complete(updateDataSource(existing, credentials, localPort))
                    return@invokeLater
                }

                // Create new data source
                val dataSource = dataSourceManager.createEmpty().apply {
                    name = dataSourceName

                    // Get PostgreSQL driver
                    val driver = DatabaseDriverManager.getInstance().getDriver("postgresql")
                    if (driver != null) {
                        databaseDriver = driver
                        driverClass = "org.postgresql.Driver"
                    }

                    // Connection URL (includes database name)
                    url = credentials.getLocalJdbcUrl(localPort)

                    // Username
                    username = credentials.username

                    // Set auth provider to user/password
                    authProviderId = "credentials"

                    // Store password forever (not just for session)
                    passwordStorage = LocalDataSource.Storage.PERSIST

                    // Comment with cluster info
                    comment = buildComment(context, cluster, isReplica)

                    // Group in folder
                    val settings = CnpgSettings.getInstance()
                    if (settings.useFolderOrganization) {
                        groupName = "$context/${cluster.namespace}"
                    }

                    // Enable automatic schema control for full introspection
                    setSchemaControl(SchemaControl.AUTOMATIC)

                    // Disable auto-sync initially to prevent connection before password is stored
                    isAutoSynchronize = false
                }

                // ADD TO MANAGER FIRST - this ensures the data source is registered
                // before we store the password. The credential storage key is based on
                // the data source's UUID, and storing before registration can cause
                // the password to be stored with a key that doesn't match what
                // Database Tools expects when retrieving.
                dataSourceManager.addDataSource(dataSource)
                log.info("Created data source: $dataSourceName (uuid: ${dataSource.uniqueId})")

                // Register for removal monitoring
                ManagedConnectionService.getInstance(project).registerDataSource(dataSource)

                // Store password via DbCredentials API
                storeDataSourcePassword(dataSource, credentials.password)

                // Complete the future so the CNPG dialog can close
                future.complete(dataSource)

                // Open the Database tool window so the user can see the new data source
                // and configure it if needed. We use ToolWindowManager which is a public API
                // instead of DataSourceManagerDialog.showDialog() which is internal.
                ApplicationManager.getApplication().invokeLater({
                    try {
                        log.info("Opening Database tool window for: $dataSourceName")
                        openDatabaseToolWindow()
                        log.info("Database tool window opened for: $dataSourceName")
                    } catch (e: Exception) {
                        log.error("Failed to open Database tool window for: ${dataSource.name}", e)
                    }
                }, ModalityState.nonModal())
            } catch (e: Exception) {
                log.error("Failed to add data source", e)
                future.completeExceptionally(e)
            }
        }, ModalityState.any())

        return future.get()
    }

    /**
     * Update an existing data source with new connection details.
     * Called internally when a data source already exists.
     * Waits for password storage to complete before returning.
     */
    private fun updateDataSource(
        dataSource: LocalDataSource,
        credentials: DatabaseCredentials,
        localPort: Int
    ): LocalDataSource {
        // Update connection URL
        dataSource.url = credentials.getLocalJdbcUrl(localPort)
        dataSource.username = credentials.username
        dataSource.authProviderId = "credentials"
        dataSource.passwordStorage = LocalDataSource.Storage.PERSIST

        // Ensure automatic schema control for full introspection
        dataSource.setSchemaControl(SchemaControl.AUTOMATIC)

        // Update password on pooled thread (not EDT) to avoid SlowOperations error
        // But wait for it to complete before returning
        val passwordFuture = CompletableFuture<Unit>()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                storeDataSourcePassword(dataSource, credentials.password)
                passwordFuture.complete(Unit)
            } catch (e: Exception) {
                log.error("Failed to store password for: ${dataSource.name}", e)
                passwordFuture.completeExceptionally(e)
            }
        }
        passwordFuture.get()

        return dataSource
    }

    /**
     * Update an existing data source's connection details.
     * Used by ManagedConnectionService to refresh connections on startup.
     *
     * @param dataSource The data source to update.
     * @param credentials Fresh credentials from Kubernetes.
     * @param localPort New local port for the port-forward.
     */
    fun updateDataSourceConnection(
        dataSource: LocalDataSource,
        credentials: DatabaseCredentials,
        localPort: Int
    ) {
        log.info("Updating data source connection: ${dataSource.name} to port $localPort")

        val future = CompletableFuture<Unit>()

        // First, disable auto-sync and update URL on EDT
        ApplicationManager.getApplication().invokeLater({
            try {
                // Safeguard: Use manager's reference to ensure we're working with
                // the registered data source instance
                val dataSourceManager = LocalDataSourceManager.getInstance(project)
                val managedDataSource = dataSourceManager.dataSources.find {
                    it.uniqueId == dataSource.uniqueId
                } ?: dataSource

                managedDataSource.isAutoSynchronize = false
                log.info("Disabled auto-sync for: ${managedDataSource.name} (uuid: ${managedDataSource.uniqueId})")

                // Update URL and username FIRST (before storing password)
                // Password storage may be keyed on these properties
                managedDataSource.url = credentials.getLocalJdbcUrl(localPort)
                managedDataSource.username = credentials.username
                managedDataSource.authProviderId = "credentials"
                managedDataSource.passwordStorage = LocalDataSource.Storage.PERSIST

                // Ensure automatic schema control for full introspection
                managedDataSource.setSchemaControl(SchemaControl.AUTOMATIC)
                log.info("Updated data source URL and properties: ${managedDataSource.name}")

                // Store password on pooled thread (after URL is set)
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        storeDataSourcePassword(managedDataSource, credentials.password)

                        // Re-enable auto-sync on EDT after password is stored
                        ApplicationManager.getApplication().invokeLater({
                            try {
                                managedDataSource.isAutoSynchronize = true
                                log.info("Enabled auto-sync for: ${managedDataSource.name}")

                                // Show notification with action to open Properties dialog
                                showConfigureDataSourceNotification(managedDataSource)

                                future.complete(Unit)
                            } catch (e: Exception) {
                                log.error("Failed to enable auto-sync for: ${managedDataSource.name}", e)
                                future.completeExceptionally(e)
                            }
                        }, ModalityState.any())
                    } catch (e: Exception) {
                        log.error("Failed to store password for: ${managedDataSource.name}", e)
                        future.completeExceptionally(e)
                    }
                }
            } catch (e: Exception) {
                log.error("Failed to update data source: ${dataSource.name}", e)
                future.completeExceptionally(e)
            }
        }, ModalityState.defaultModalityState())

        future.get()
    }

    /**
     * Remove the data source for a cluster.
     *
     * @param cluster The cluster to remove.
     */
    fun removeDataSource(cluster: CnpgCluster) {
        val dataSourceManager = LocalDataSourceManager.getInstance(project)

        // Find and remove any data sources for this cluster
        val toRemove = dataSourceManager.dataSources.filter { dataSource ->
            dataSource.comment?.contains(cluster.key) == true ||
            dataSource.name.contains(cluster.name)
        }

        toRemove.forEach { dataSource ->
            log.info("Removing data source: ${dataSource.name}")
            removeDataSourcePassword(dataSource)
            dataSourceManager.removeDataSource(dataSource)
        }
    }

    /**
     * Remove a specific data source by name.
     */
    fun removeDataSource(name: String) {
        val dataSourceManager = LocalDataSourceManager.getInstance(project)
        val dataSource = dataSourceManager.dataSources.find { it.name == name }
        if (dataSource != null) {
            removeDataSourcePassword(dataSource)
            dataSourceManager.removeDataSource(dataSource)
        }
    }

    /**
     * Find an existing data source for a cluster.
     */
    fun findDataSource(cluster: CnpgCluster): LocalDataSource? {
        val dataSourceManager = LocalDataSourceManager.getInstance(project)
        return dataSourceManager.dataSources.find { dataSource ->
            dataSource.comment?.contains(cluster.key) == true
        }
    }

    /**
     * Generate a data source name based on settings.
     */
    private fun generateDataSourceName(
        context: String,
        cluster: CnpgCluster,
        isReplica: Boolean
    ): String {
        val settings = CnpgSettings.getInstance()
        val pattern = settings.dataSourceNamingPattern

        var name = pattern
            .replace("\${context}", context)
            .replace("\${namespace}", cluster.namespace)
            .replace("\${name}", cluster.name)

        if (isReplica) {
            name = "$name (RO)"
        }

        return name
    }

    /**
     * Build a comment for the data source.
     */
    private fun buildComment(
        context: String,
        cluster: CnpgCluster,
        isReplica: Boolean
    ): String {
        return buildString {
            append("CloudNativePG: ${cluster.key}")
            append("\nContext: $context")
            append("\nType: ${if (isReplica) "Read Replica" else "Primary"}")
            cluster.version?.let { append("\nPostgreSQL: $it") }
        }
    }

    /**
     * Store password in Database Tools credential store.
     * This uses the proper API so Database Tools can find the password.
     *
     * IMPORTANT: The data source must be registered with LocalDataSourceManager
     * before calling this method. The credential storage key is based on the
     * data source's UUID, and storing before registration can cause mismatched keys.
     */
    private fun storeDataSourcePassword(dataSource: LocalDataSource, password: String) {
        log.info("Storing password for: ${dataSource.name} (uuid: ${dataSource.uniqueId})")
        try {
            // Method 1: Use the Database Tools credential store API
            val oneTimePassword = OneTimeString(password.toCharArray())
            DbCredentials.getInstance().storePassword(dataSource, oneTimePassword)
            log.info("Password stored via DbCredentials for: ${dataSource.name}")

            // Method 2: Also store directly in PasswordSafe with the expected service name
            // Database Tools uses: generateServiceName("DB", uuid) = "IntelliJ Platform DB — <uuid>"
            val serviceName = "IntelliJ Platform DB \u2014 ${dataSource.uniqueId}"
            val credentialAttributes = CredentialAttributes(serviceName, dataSource.username)
            val credentials = Credentials(dataSource.username, password)
            PasswordSafe.instance.set(credentialAttributes, credentials)
            log.info("Password also stored via PasswordSafe with service: $serviceName")
        } catch (e: Exception) {
            log.error("Failed to store password for: ${dataSource.name} (uuid: ${dataSource.uniqueId})", e)
        }
    }

    /**
     * Remove password from Database Tools credential store.
     */
    private fun removeDataSourcePassword(dataSource: LocalDataSource) {
        try {
            DbCredentials.getInstance().storePassword(dataSource, null)
        } catch (e: Exception) {
            log.warn("Failed to remove password for data source: ${dataSource.name}", e)
        }
    }

    /**
     * Show a notification prompting the user to configure the data source.
     * This is used on reconnection to avoid intrusive dialogs on IDE startup.
     */
    private fun showConfigureDataSourceNotification(dataSource: LocalDataSource) {
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("CloudNativePG Connector - Action Required")

        val notification = notificationGroup.createNotification(
            "Database Connection Updated",
            "Click 'Open Database' to view ${dataSource.name}. Right-click the data source to edit properties.",
            NotificationType.INFORMATION
        )

        notification.addAction(NotificationAction.createSimple("Open Database") {
            openDatabaseToolWindow()
            notification.expire()
        })

        notification.notify(project)
    }

    /**
     * Open the Database tool window.
     * This is a public API alternative to DataSourceManagerDialog.showDialog().
     */
    private fun openDatabaseToolWindow() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Database")
        if (toolWindow != null) {
            toolWindow.show()
        } else {
            log.warn("Database tool window not found")
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): DatabaseToolsService = project.service()
    }
}
