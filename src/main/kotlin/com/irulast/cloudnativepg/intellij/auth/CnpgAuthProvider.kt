package com.irulast.cloudnativepg.intellij.auth

import com.irulast.cloudnativepg.intellij.kubernetes.CnpgClusterDiscovery
import com.irulast.cloudnativepg.intellij.kubernetes.KubernetesClientProvider
import com.irulast.cloudnativepg.intellij.models.CnpgCluster
import com.irulast.cloudnativepg.intellij.services.PortForwardException
import com.irulast.cloudnativepg.intellij.services.PortForwardService
import com.intellij.database.dataSource.DatabaseAuthProvider
import com.intellij.database.dataSource.DatabaseConnectionInterceptor.ProtoConnection
import com.intellij.database.dataSource.DatabaseConnectionPoint
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.openapi.diagnostic.Logger

/**
 * Authentication provider for CloudNativePG clusters.
 *
 * Handles the full connection lifecycle:
 * 1. Establishes a kubectl port-forward to the target pod
 * 2. Fetches fresh credentials from the Kubernetes secret
 * 3. Injects the real localhost URL and credentials into the connection
 *
 * This eliminates the need for storing passwords or manual credential sync.
 */
class CnpgAuthProvider : DatabaseAuthProvider {

    private val log = Logger.getInstance(CnpgAuthProvider::class.java)

    override fun getId(): String = AUTH_PROVIDER_ID

    override fun getDisplayName(): String = "Kubernetes Secret (CloudNativePG)"

    override fun getApplicability(
        point: DatabaseConnectionPoint,
        level: DatabaseAuthProvider.ApplicabilityLevel
    ): DatabaseAuthProvider.ApplicabilityLevel.Result {
        @Suppress("DEPRECATION")
        val context = point.additionalProperties[PROP_CONTEXT]
        return if (!context.isNullOrBlank()) {
            DatabaseAuthProvider.ApplicabilityLevel.Result.APPLICABLE
        } else {
            DatabaseAuthProvider.ApplicabilityLevel.Result.NOT_APPLICABLE
        }
    }

    override suspend fun interceptConnection(proto: ProtoConnection, silent: Boolean): Boolean {
        @Suppress("DEPRECATION")
        val props = proto.connectionPoint.additionalProperties
        val context = props[PROP_CONTEXT] ?: return false
        val namespace = props[PROP_NAMESPACE] ?: return false
        val clusterName = props[PROP_CLUSTER] ?: return false
        val secretName = props[PROP_SECRET] ?: return false
        val useReplica = props[PROP_REPLICA]?.toBoolean() ?: false

        log.info("Intercepting connection for $namespace/$clusterName (replica=$useReplica)")

        val clientProvider = KubernetesClientProvider.getInstance()
        val client = clientProvider.getClient(context)
        val discovery = CnpgClusterDiscovery(client)

        // Fetch fresh cluster state to get current primary pod
        val cluster = discovery.getCluster(clusterName, namespace)
            ?: throw PortForwardException("Cluster $namespace/$clusterName not found in context $context")

        // Start or reuse port-forward
        val connState = PortForwardService.getInstance().startPortForward(context, cluster, useReplica)

        // Fetch fresh credentials from K8s secret
        val credentials = discovery.getCredentials(cluster)

        // Inject real URL and credentials
        proto.url = "jdbc:postgresql://localhost:${connState.localPort}/${credentials.database}"
        proto.connectionProperties["user"] = credentials.username
        proto.connectionProperties["password"] = credentials.password

        log.info("Connection intercepted: localhost:${connState.localPort} -> $namespace/$clusterName")
        return true
    }

    override suspend fun handleConnectionFailure(
        proto: ProtoConnection,
        e: Throwable,
        silent: Boolean,
        attempt: Int
    ): Boolean {
        val error = e
        if (attempt >= MAX_RETRY_ATTEMPTS) return false

        // Retry on port-forward failures
        if (error is PortForwardException || error.cause is PortForwardException) {
            log.warn("Port-forward failure (attempt $attempt), will retry: ${error.message}")

            // Force restart: stop existing port-forward so startPortForward creates a fresh one
            @Suppress("DEPRECATION")
            val props = proto.connectionPoint.additionalProperties
            val context = props[PROP_CONTEXT] ?: return false
            val namespace = props[PROP_NAMESPACE] ?: return false
            val clusterName = props[PROP_CLUSTER] ?: return false

            try {
                val client = KubernetesClientProvider.getInstance().getClient(context)
                val cluster = CnpgClusterDiscovery(client).getCluster(clusterName, namespace) ?: return false
                PortForwardService.getInstance().stopPortForward(context, cluster)
            } catch (e: Exception) {
                log.warn("Failed to clean up port-forward for retry", e)
            }

            return true // signal IDE to retry interceptConnection
        }

        return false
    }

    companion object {
        const val AUTH_PROVIDER_ID = "cnpg-k8s-secret"

        // Additional property keys stored on the data source
        const val PROP_CONTEXT = "cnpg.context"
        const val PROP_NAMESPACE = "cnpg.namespace"
        const val PROP_CLUSTER = "cnpg.cluster"
        const val PROP_SECRET = "cnpg.secret"
        const val PROP_REPLICA = "cnpg.replica"

        private const val MAX_RETRY_ATTEMPTS = 3
    }
}
