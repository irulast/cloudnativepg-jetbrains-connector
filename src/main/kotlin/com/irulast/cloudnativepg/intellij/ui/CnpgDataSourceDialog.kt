package com.irulast.cloudnativepg.intellij.ui

import com.irulast.cloudnativepg.intellij.kubernetes.CnpgClusterDiscovery
import com.irulast.cloudnativepg.intellij.kubernetes.KubernetesClientProvider
import com.irulast.cloudnativepg.intellij.models.CnpgCluster
import com.irulast.cloudnativepg.intellij.services.CnpgService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Dialog for selecting a CloudNativePG cluster to create a data source.
 */
class CnpgDataSourceDialog(private val project: Project) : DialogWrapper(project) {

    private val log = Logger.getInstance(CnpgDataSourceDialog::class.java)

    private val contextComboBox = ComboBox<String>()
    private val namespaceComboBox = ComboBox<String>()
    private val clusterComboBox = ComboBox<CnpgCluster>()
    private val connectToReplicaCheckbox = JBCheckBox("Connect to read replica")
    private val statusLabel = JBLabel("")

    private var allClusters: List<CnpgCluster> = emptyList()
    private var isInitializing = true
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    init {
        title = "New Data Source from CloudNativePG"
        setOKButtonText("Connect")
        init()
    }

    override fun createCenterPanel(): JComponent {
        // Configure combo boxes
        contextComboBox.renderer = SimpleListCellRenderer.create("Select context...") { it ?: "Select context..." }
        namespaceComboBox.renderer = SimpleListCellRenderer.create("") { it ?: "" }
        clusterComboBox.renderer = SimpleListCellRenderer.create("") { cluster ->
            if (cluster != null) "${cluster.name} (${cluster.readyInstances}/${cluster.instances} ready)" else ""
        }

        // Add listeners (but skip during initialization)
        contextComboBox.addItemListener { e ->
            if (!isInitializing && e.stateChange == ItemEvent.SELECTED) {
                loadClusters()
            }
        }

        namespaceComboBox.addItemListener { e ->
            if (!isInitializing && e.stateChange == ItemEvent.SELECTED) {
                filterClustersByNamespace()
            }
        }

        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Kubernetes Context:"), contextComboBox)
            .addLabeledComponent(JBLabel("Namespace:"), namespaceComboBox)
            .addLabeledComponent(JBLabel("Cluster:"), clusterComboBox)
            .addComponent(connectToReplicaCheckbox)
            .addComponent(statusLabel)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        panel.preferredSize = Dimension(450, 200)

        // Load contexts after panel is created
        SwingUtilities.invokeLater {
            loadContexts()
        }

        return panel
    }

    private fun loadContexts() {
        statusLabel.text = "Loading Kubernetes contexts..."
        isOKActionEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val provider = KubernetesClientProvider.getInstance()
                val contexts = provider.listContexts()
                val currentContext = provider.getCurrentContext()

                log.info("Loaded ${contexts.size} contexts, current: $currentContext")

                ApplicationManager.getApplication().invokeLater({
                    isInitializing = true
                    contextComboBox.model = DefaultComboBoxModel(contexts.toTypedArray())
                    if (contexts.contains(currentContext)) {
                        contextComboBox.selectedItem = currentContext
                    } else if (contexts.isNotEmpty()) {
                        contextComboBox.selectedIndex = 0
                    }
                    isInitializing = false
                    statusLabel.text = ""
                    loadClusters()
                }, ModalityState.stateForComponent(contextComboBox))
            } catch (e: Exception) {
                log.warn("Failed to load Kubernetes contexts", e)
                ApplicationManager.getApplication().invokeLater({
                    statusLabel.text = "Failed to load contexts: ${e.message}"
                    isInitializing = false
                }, ModalityState.stateForComponent(contextComboBox))
            }
        }
    }

    private fun loadClusters() {
        val context = contextComboBox.selectedItem as? String
        if (context == null) {
            statusLabel.text = "Please select a Kubernetes context"
            return
        }

        statusLabel.text = "Loading clusters from $context..."
        isOKActionEnabled = false
        clusterComboBox.model = DefaultComboBoxModel()
        namespaceComboBox.model = DefaultComboBoxModel()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesClientProvider.getInstance().getClient(context)
                val discovery = CnpgClusterDiscovery(client)
                val clusters = discovery.listClusters()

                log.info("Found ${clusters.size} CNPG clusters")

                ApplicationManager.getApplication().invokeLater({
                    allClusters = clusters

                    if (clusters.isEmpty()) {
                        statusLabel.text = "No CloudNativePG clusters found"
                        isOKActionEnabled = false
                        return@invokeLater
                    }

                    // Extract unique namespaces
                    val namespaces = listOf("All Namespaces") + clusters.map { it.namespace }.distinct().sorted()

                    isInitializing = true
                    namespaceComboBox.model = DefaultComboBoxModel(namespaces.toTypedArray())
                    namespaceComboBox.selectedIndex = 0
                    isInitializing = false

                    filterClustersByNamespace()
                    statusLabel.text = "Found ${clusters.size} cluster(s)"
                    isOKActionEnabled = true
                }, ModalityState.stateForComponent(clusterComboBox))
            } catch (e: Exception) {
                log.warn("Failed to load CNPG clusters", e)
                ApplicationManager.getApplication().invokeLater({
                    statusLabel.text = "Failed to load clusters: ${e.message}"
                    isOKActionEnabled = false
                }, ModalityState.stateForComponent(clusterComboBox))
            }
        }
    }

    private fun filterClustersByNamespace() {
        val namespace = namespaceComboBox.selectedItem as? String ?: return

        val filtered = if (namespace == "All Namespaces") {
            allClusters
        } else {
            allClusters.filter { it.namespace == namespace }
        }

        clusterComboBox.model = DefaultComboBoxModel(filtered.toTypedArray())
        if (filtered.isNotEmpty()) {
            clusterComboBox.selectedIndex = 0
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (contextComboBox.selectedItem == null) {
            return ValidationInfo("Please select a Kubernetes context", contextComboBox)
        }
        if (clusterComboBox.selectedItem == null) {
            return ValidationInfo("Please select a CloudNativePG cluster", clusterComboBox)
        }
        return null
    }

    override fun doOKAction() {
        val context = contextComboBox.selectedItem as? String ?: return
        val cluster = clusterComboBox.selectedItem as? CnpgCluster ?: return
        val useReplica = connectToReplicaCheckbox.isSelected

        statusLabel.text = "Connecting to ${cluster.displayName}..."
        isOKActionEnabled = false

        coroutineScope.launch {
            try {
                val service = CnpgService.getInstance()
                service.connect(project, context, cluster, useReplica)

                ApplicationManager.getApplication().invokeLater({
                    close(OK_EXIT_CODE)
                }, ModalityState.stateForComponent(statusLabel))
            } catch (e: Exception) {
                log.error("Failed to connect to cluster", e)
                ApplicationManager.getApplication().invokeLater({
                    statusLabel.text = "Connection failed: ${e.message}"
                    isOKActionEnabled = true
                }, ModalityState.stateForComponent(statusLabel))
            }
        }
    }
}
