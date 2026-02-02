package com.irulast.cloudnativepg.intellij.kubernetes

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides Kubernetes client instances with smart configuration resolution.
 *
 * Configuration resolution order:
 * 1. KUBECONFIG environment variable
 * 2. ~/.kube/config (default location)
 */
@Service(Service.Level.APP)
class KubernetesClientProvider : Disposable {

    private val log = Logger.getInstance(KubernetesClientProvider::class.java)
    private val clientCache = ConcurrentHashMap<String, KubernetesClient>()
    private var cachedConfig: Config? = null

    /**
     * Get a Kubernetes client for the specified context.
     *
     * @param context The Kubernetes context name, or null for the current context.
     * @return A configured KubernetesClient instance.
     */
    fun getClient(context: String? = null): KubernetesClient {
        val effectiveContext = context ?: getCurrentContext()
        return clientCache.computeIfAbsent(effectiveContext) { ctx ->
            createClient(ctx)
        }
    }

    /**
     * Create a new Kubernetes client for the specified context.
     */
    private fun createClient(context: String): KubernetesClient {
        log.info("Creating Kubernetes client for context: $context")

        // Use Config.autoConfigure with the context name
        val config = Config.autoConfigure(context)

        return KubernetesClientBuilder()
            .withConfig(config)
            .build()
    }

    /**
     * List available Kubernetes contexts.
     */
    fun listContexts(): List<String> {
        return try {
            val config = getConfig()
            config.contexts?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            log.warn("Failed to list Kubernetes contexts", e)
            emptyList()
        }
    }

    /**
     * Get the current/default context name.
     */
    fun getCurrentContext(): String {
        return try {
            getConfig().currentContext?.name ?: "default"
        } catch (e: Exception) {
            log.warn("Failed to get current Kubernetes context", e)
            "default"
        }
    }

    /**
     * Get the kubeconfig file path.
     */
    fun getKubeconfigPath(): String {
        // Check KUBECONFIG env var first
        val envPath = System.getenv("KUBECONFIG")
        if (!envPath.isNullOrBlank()) {
            // KUBECONFIG can be a colon-separated list; use the first one
            val firstPath = envPath.split(File.pathSeparator).firstOrNull()
            if (firstPath != null && File(firstPath).exists()) {
                return firstPath
            }
        }

        // Fall back to default location
        val defaultPath = "${System.getProperty("user.home")}/.kube/config"
        return defaultPath
    }

    /**
     * Check if a valid kubeconfig exists.
     */
    fun hasValidConfig(): Boolean {
        return try {
            val config = getConfig()
            config.contexts?.isNotEmpty() == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the cached kubeconfig or load it.
     */
    private fun getConfig(): Config {
        return cachedConfig ?: Config.autoConfigure(null).also {
            cachedConfig = it
        }
    }

    /**
     * Refresh the configuration (invalidate cache).
     */
    fun refresh() {
        log.info("Refreshing Kubernetes configuration")
        cachedConfig = null
        clientCache.values.forEach { client ->
            try {
                client.close()
            } catch (e: Exception) {
                log.warn("Error closing Kubernetes client", e)
            }
        }
        clientCache.clear()
    }

    override fun dispose() {
        clientCache.values.forEach { client ->
            try {
                client.close()
            } catch (e: Exception) {
                log.warn("Error closing Kubernetes client during disposal", e)
            }
        }
        clientCache.clear()
    }

    companion object {
        @JvmStatic
        fun getInstance(): KubernetesClientProvider = service()
    }
}
