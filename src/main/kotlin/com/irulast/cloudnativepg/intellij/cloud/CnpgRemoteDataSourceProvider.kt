package com.irulast.cloudnativepg.intellij.cloud

import com.irulast.cloudnativepg.intellij.auth.CnpgAuthProvider
import com.irulast.cloudnativepg.intellij.kubernetes.CnpgClusterDiscovery
import com.irulast.cloudnativepg.intellij.kubernetes.KubernetesClientProvider
import com.irulast.cloudnativepg.intellij.settings.CnpgSettings
import com.intellij.database.cloud.explorer.CloudConnectionData
import com.intellij.database.cloud.explorer.RemoteDataSourceProvider
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.withContext
import java.util.function.Consumer

/**
 * Discovers CloudNativePG clusters and emits pre-configured data sources.
 * Each discovered cluster becomes a data source with the CNPG auth provider,
 * which handles port-forwarding and credential injection at connection time.
 */
class CnpgRemoteDataSourceProvider(
    private val connectionData: CloudConnectionData,
    private val scope: CoroutineScope,
    private val errorCallback: Consumer<Exception>
) : RemoteDataSourceProvider {

    private val log = Logger.getInstance(CnpgRemoteDataSourceProvider::class.java)
    private val data = connectionData as CnpgCloudConnectionData

    override fun getDisplayName(): String = "CloudNativePG (${data.context})"

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun getDataSources(project: Project?): ReceiveChannel<LocalDataSource> = scope.produce {
        try {
            val clusters = withContext(Dispatchers.IO) {
                val client = KubernetesClientProvider.getInstance().getClient(data.context)
                val discovery = CnpgClusterDiscovery(client)
                val namespace = data.namespaceFilter.ifBlank { null }
                discovery.listClusters(namespace)
            }

            val settings = CnpgSettings.getInstance()
            val dsManager = project?.let { LocalDataSourceManager.getInstance(it) } ?: return@produce

            for (cluster in clusters) {
                try {
                    val credentials = withContext(Dispatchers.IO) {
                        val client = KubernetesClientProvider.getInstance().getClient(data.context)
                        CnpgClusterDiscovery(client).getCredentials(cluster)
                    }

                    val dataSource = dsManager.createEmpty().apply {
                        name = buildDataSourceName(settings, data.context, cluster)
                        url = "jdbc:postgresql://localhost/${credentials.database}"
                        username = credentials.username

                        val driver = DatabaseDriverManager.getInstance().getDriver("postgresql")
                        if (driver != null) {
                            databaseDriver = driver
                            driverClass = "org.postgresql.Driver"
                        }

                        authProviderId = CnpgAuthProvider.AUTH_PROVIDER_ID

                        setAdditionalProperty(CnpgAuthProvider.PROP_CONTEXT, data.context)
                        setAdditionalProperty(CnpgAuthProvider.PROP_NAMESPACE, cluster.namespace)
                        setAdditionalProperty(CnpgAuthProvider.PROP_CLUSTER, cluster.name)
                        setAdditionalProperty(CnpgAuthProvider.PROP_SECRET, cluster.secretName)
                        setAdditionalProperty(CnpgAuthProvider.PROP_REPLICA, "false")

                        comment = "CloudNativePG: ${cluster.key}\n" +
                                "Context: ${data.context}\n" +
                                "Type: Primary\n" +
                                (cluster.version?.let { "PostgreSQL: $it\n" } ?: "") +
                                "${cluster.readyInstances}/${cluster.instances} instances ready"

                        if (settings.useFolderOrganization) {
                            groupName = "${data.context}/${cluster.namespace}"
                        }
                    }

                    send(dataSource)
                } catch (e: Exception) {
                    log.warn("Failed to create data source for cluster ${cluster.displayName}", e)
                }
            }
        } catch (e: Exception) {
            log.error("Failed to discover CloudNativePG clusters", e)
            errorCallback.accept(e)
        }
    }

    override fun hasNewDataSources(): Boolean = true

    override fun testConnection(project: Project?): String {
        return try {
            val client = KubernetesClientProvider.getInstance().getClient(data.context)
            client.namespaces().list()
            "" // empty string = success
        } catch (e: Exception) {
            "Failed to connect to Kubernetes context '${data.context}': ${e.message}"
        }
    }

    override fun dispose() {
        // No resources to clean up
    }

    private fun buildDataSourceName(
        settings: CnpgSettings,
        context: String,
        cluster: com.irulast.cloudnativepg.intellij.models.CnpgCluster
    ): String {
        return settings.dataSourceNamingPattern
            .replace("\${context}", context)
            .replace("\${namespace}", cluster.namespace)
            .replace("\${name}", cluster.name)
    }
}
