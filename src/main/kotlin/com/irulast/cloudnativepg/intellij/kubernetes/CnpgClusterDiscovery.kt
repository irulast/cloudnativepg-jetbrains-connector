package com.irulast.cloudnativepg.intellij.kubernetes

import com.irulast.cloudnativepg.intellij.models.CnpgCluster
import com.irulast.cloudnativepg.intellij.models.DatabaseCredentials
import com.intellij.openapi.diagnostic.Logger
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext

/**
 * Discovers CloudNativePG clusters in Kubernetes.
 */
class CnpgClusterDiscovery(private val client: KubernetesClient) {

    private val log = Logger.getInstance(CnpgClusterDiscovery::class.java)

    /**
     * CRD context for CloudNativePG clusters.
     */
    private val crdContext = CustomResourceDefinitionContext.Builder()
        .withGroup(CnpgCluster.API_GROUP)
        .withVersion(CnpgCluster.API_VERSION)
        .withPlural(CnpgCluster.RESOURCE_PLURAL)
        .withScope("Namespaced")
        .build()

    /**
     * List all CloudNativePG clusters.
     *
     * @param namespace Namespace to filter by, or null for all namespaces.
     * @return List of discovered clusters.
     */
    fun listClusters(namespace: String? = null): List<CnpgCluster> {
        log.info("Listing CNPG clusters in namespace: ${namespace ?: "all"}")

        return try {
            val resources = if (namespace != null) {
                client.genericKubernetesResources(crdContext)
                    .inNamespace(namespace)
                    .list()
                    .items
            } else {
                client.genericKubernetesResources(crdContext)
                    .inAnyNamespace()
                    .list()
                    .items
            }

            resources.mapNotNull { resource ->
                try {
                    parseCluster(resource)
                } catch (e: Exception) {
                    log.warn("Failed to parse CNPG cluster: ${resource.metadata?.name}", e)
                    null
                }
            }
        } catch (e: Exception) {
            log.error("Failed to list CNPG clusters", e)
            throw CnpgDiscoveryException("Failed to list clusters: ${e.message}", e)
        }
    }

    /**
     * Get credentials for a cluster from its app secret.
     *
     * @param cluster The cluster to get credentials for.
     * @return Database credentials.
     */
    fun getCredentials(cluster: CnpgCluster): DatabaseCredentials {
        log.info("Getting credentials for cluster: ${cluster.displayName}")

        return try {
            val secret = client.secrets()
                .inNamespace(cluster.namespace)
                .withName(cluster.secretName)
                .get() ?: throw CnpgDiscoveryException(
                "Secret not found: ${cluster.namespace}/${cluster.secretName}"
            )

            SecretDecoder.extractCredentials(secret)
        } catch (e: CnpgDiscoveryException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to get credentials for cluster: ${cluster.displayName}", e)
            throw CnpgDiscoveryException(
                "Failed to get credentials for ${cluster.displayName}: ${e.message}", e
            )
        }
    }

    /**
     * Get a specific cluster by name and namespace.
     */
    fun getCluster(name: String, namespace: String): CnpgCluster? {
        return try {
            val resource = client.genericKubernetesResources(crdContext)
                .inNamespace(namespace)
                .withName(name)
                .get()

            resource?.let { parseCluster(it) }
        } catch (e: Exception) {
            log.warn("Failed to get cluster $namespace/$name", e)
            null
        }
    }

    /**
     * Parse a GenericKubernetesResource into a CnpgCluster.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseCluster(resource: GenericKubernetesResource): CnpgCluster {
        val metadata = resource.metadata
        val name = metadata.name ?: throw IllegalArgumentException("Cluster has no name")
        val namespace = metadata.namespace ?: throw IllegalArgumentException("Cluster has no namespace")

        val spec = resource.additionalProperties["spec"] as? Map<String, Any?> ?: emptyMap()
        val status = resource.additionalProperties["status"] as? Map<String, Any?> ?: emptyMap()

        val instances = (spec["instances"] as? Number)?.toInt() ?: 1
        val readyInstances = (status["readyInstances"] as? Number)?.toInt() ?: 0
        val phase = (status["phase"] as? String) ?: "Unknown"
        val currentPrimary = status["currentPrimary"] as? String

        // Extract PostgreSQL version from imageName or status
        val version = extractVersion(spec, status)

        return CnpgCluster(
            name = name,
            namespace = namespace,
            instances = instances,
            readyInstances = readyInstances,
            phase = phase,
            primaryPod = currentPrimary,
            version = version,
            serviceRw = "$name-rw",
            serviceRo = "$name-ro",
            secretName = "$name-app"
        )
    }

    /**
     * Extract PostgreSQL version from cluster spec or status.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractVersion(spec: Map<String, Any?>, @Suppress("UNUSED_PARAMETER") status: Map<String, Any?>): String? {
        // Try to get from imageName (e.g., "ghcr.io/cloudnative-pg/postgresql:16.1")
        val imageName = spec["imageName"] as? String
        if (imageName != null) {
            val versionMatch = Regex(":([0-9]+(?:\\.[0-9]+)*)").find(imageName)
            if (versionMatch != null) {
                return versionMatch.groupValues[1]
            }
        }

        // Try postgresql spec
        val postgresql = spec["postgresql"] as? Map<String, Any?>
        val pgVersion = postgresql?.get("version") as? String
        if (pgVersion != null) {
            return pgVersion
        }

        return null
    }
}

/**
 * Exception for CNPG discovery errors.
 */
class CnpgDiscoveryException(message: String, cause: Throwable? = null) : Exception(message, cause)
