package com.irulast.cloudnativepg.intellij.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Persistent settings for the CloudNativePG Connector plugin.
 */
@Service(Service.Level.APP)
@State(
    name = "CnpgSettings",
    storages = [Storage("cloudnativepg-connector.xml")]
)
class CnpgSettings : PersistentStateComponent<CnpgSettings.State> {

    private var myState = State()

    /**
     * Serializable state class.
     */
    data class State(
        var portRangeStart: Int = DEFAULT_PORT_RANGE_START,
        var portRangeEnd: Int = DEFAULT_PORT_RANGE_END,
        var autoReconnect: Boolean = true,
        var autoReconnectOnStartup: Boolean = true,
        var monitorConnectionHealth: Boolean = false,
        var showNotifications: Boolean = true,
        var autoAddToDbTools: Boolean = true,
        var autoRemoveOnDisconnect: Boolean = false,
        var dataSourceNamingPattern: String = DEFAULT_NAMING_PATTERN,
        var useFolderOrganization: Boolean = true,
        var markReplicaReadOnly: Boolean = true
    )

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    // Convenience accessors
    var portRangeStart: Int
        get() = myState.portRangeStart
        set(value) { myState.portRangeStart = value }

    var portRangeEnd: Int
        get() = myState.portRangeEnd
        set(value) { myState.portRangeEnd = value }

    var autoReconnect: Boolean
        get() = myState.autoReconnect
        set(value) { myState.autoReconnect = value }

    var autoReconnectOnStartup: Boolean
        get() = myState.autoReconnectOnStartup
        set(value) { myState.autoReconnectOnStartup = value }

    var monitorConnectionHealth: Boolean
        get() = myState.monitorConnectionHealth
        set(value) { myState.monitorConnectionHealth = value }

    var showNotifications: Boolean
        get() = myState.showNotifications
        set(value) { myState.showNotifications = value }

    var autoAddToDbTools: Boolean
        get() = myState.autoAddToDbTools
        set(value) { myState.autoAddToDbTools = value }

    var autoRemoveOnDisconnect: Boolean
        get() = myState.autoRemoveOnDisconnect
        set(value) { myState.autoRemoveOnDisconnect = value }

    var dataSourceNamingPattern: String
        get() = myState.dataSourceNamingPattern
        set(value) { myState.dataSourceNamingPattern = value }

    var useFolderOrganization: Boolean
        get() = myState.useFolderOrganization
        set(value) { myState.useFolderOrganization = value }

    var markReplicaReadOnly: Boolean
        get() = myState.markReplicaReadOnly
        set(value) { myState.markReplicaReadOnly = value }

    companion object {
        const val DEFAULT_PORT_RANGE_START = 15432
        const val DEFAULT_PORT_RANGE_END = 15532
        const val DEFAULT_NAMING_PATTERN = "\${namespace}/\${name}"

        /**
         * Available naming pattern variables.
         */
        val NAMING_PATTERN_VARIABLES = listOf(
            "\${context}" to "Kubernetes context name",
            "\${namespace}" to "Kubernetes namespace",
            "\${name}" to "Cluster name"
        )

        /**
         * Predefined naming patterns.
         */
        val NAMING_PATTERNS = listOf(
            "\${namespace}/\${name}" to "namespace/name",
            "\${name}" to "name only",
            "\${context}/\${namespace}/\${name}" to "context/namespace/name",
            "[CNPG] \${name}" to "[CNPG] name"
        )

        @JvmStatic
        fun getInstance(): CnpgSettings = service()
    }
}
