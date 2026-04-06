package com.irulast.cloudnativepg.intellij.cloud

import com.irulast.cloudnativepg.intellij.kubernetes.KubernetesClientProvider
import com.intellij.database.cloud.explorer.CloudConnectionData
import com.intellij.database.cloud.explorer.CloudConnectionUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.DefaultComboBoxModel
import javax.swing.JPanel

/**
 * UI panel for configuring a CloudNativePG cloud connection.
 * Shows a Kubernetes context selector and optional namespace filter.
 */
class CnpgCloudConnectionUI(
    private val project: Project,
    private val connectionData: CloudConnectionData,
    private val scope: CoroutineScope
) : CloudConnectionUI {

    private val data = connectionData as CnpgCloudConnectionData
    private val contextModel = DefaultComboBoxModel<String>()
    private var selectedContext: String = data.context
    private var namespaceFilter: String = data.namespaceFilter

    private val mainPanel: JPanel = panel {
        group("Kubernetes Connection") {
            row("Context:") {
                comboBox(contextModel)
                    .bindItem(
                        { selectedContext },
                        { selectedContext = it ?: "" }
                    )
                    .comment("Select the Kubernetes context with CloudNativePG clusters")
            }
            row("Namespace filter:") {
                textField()
                    .bindText(
                        { namespaceFilter },
                        { namespaceFilter = it }
                    )
                    .comment("Leave empty to discover clusters in all namespaces")
            }
        }
    }

    init {
        loadContexts()
    }

    private fun loadContexts() {
        scope.launch {
            val contexts = withContext(Dispatchers.IO) {
                KubernetesClientProvider.getInstance().listContexts()
            }
            val current = withContext(Dispatchers.IO) {
                KubernetesClientProvider.getInstance().getCurrentContext()
            }

            contextModel.removeAllElements()
            contexts.forEach { contextModel.addElement(it) }

            if (selectedContext.isBlank() && contexts.isNotEmpty()) {
                selectedContext = current.ifBlank { contexts.first() }
                contextModel.selectedItem = selectedContext
            } else if (selectedContext.isNotBlank()) {
                contextModel.selectedItem = selectedContext
            }
        }
    }

    override fun getComponent(): JPanel = mainPanel

    override fun saveFields(data: CloudConnectionData) {
        val d = data as CnpgCloudConnectionData
        d.context = selectedContext
        d.namespaceFilter = namespaceFilter
    }

    override fun isModified(data: CloudConnectionData): Boolean {
        val d = data as CnpgCloudConnectionData
        return d.context != selectedContext || d.namespaceFilter != namespaceFilter
    }

    override fun reset(data: CloudConnectionData) {
        val d = data as CnpgCloudConnectionData
        selectedContext = d.context
        namespaceFilter = d.namespaceFilter
        contextModel.selectedItem = selectedContext
    }

    override fun onTestConnectionFinished() {
        // No special action needed
    }
}
