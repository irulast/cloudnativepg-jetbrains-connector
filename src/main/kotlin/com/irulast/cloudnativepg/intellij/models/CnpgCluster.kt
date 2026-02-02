package com.irulast.cloudnativepg.intellij.models

/**
 * Represents a CloudNativePG cluster discovered in Kubernetes.
 */
data class CnpgCluster(
    val name: String,
    val namespace: String,
    val instances: Int,
    val readyInstances: Int,
    val phase: String,
    val primaryPod: String?,
    val version: String?,
    val serviceRw: String,
    val serviceRo: String,
    val secretName: String
) {
    /**
     * Whether the cluster is in a healthy state.
     */
    val isHealthy: Boolean
        get() = phase.contains("healthy", ignoreCase = true) ||
                phase.equals("Cluster in healthy state", ignoreCase = true)

    /**
     * Display name for UI (namespace/name format).
     */
    val displayName: String
        get() = "$namespace/$name"

    /**
     * Unique key for this cluster.
     */
    val key: String
        get() = "$namespace/$name"

    /**
     * Status text for display.
     */
    val statusText: String
        get() = buildString {
            append(phase)
            append(" | ")
            append("$readyInstances/$instances instances")
            version?.let { append(" | v$it") }
        }

    companion object {
        /**
         * CloudNativePG CRD API group.
         */
        const val API_GROUP = "postgresql.cnpg.io"

        /**
         * CloudNativePG CRD API version.
         */
        const val API_VERSION = "v1"

        /**
         * CloudNativePG CRD resource plural name.
         */
        const val RESOURCE_PLURAL = "clusters"
    }
}
