package com.irulast.cloudnativepg.intellij.ui

import com.irulast.cloudnativepg.intellij.kubernetes.KubernetesClientProvider
import com.irulast.cloudnativepg.intellij.models.CnpgCluster
import com.irulast.cloudnativepg.intellij.models.ConnectionState
import com.irulast.cloudnativepg.intellij.services.CnpgService
import com.irulast.cloudnativepg.intellij.services.ConnectionManager
import com.irulast.cloudnativepg.intellij.services.DatabaseToolsService
import com.irulast.cloudnativepg.intellij.services.ManagedConnectionService
import com.irulast.cloudnativepg.intellij.services.PortForwardService
import com.irulast.cloudnativepg.intellij.settings.CnpgSettings
import com.irulast.cloudnativepg.intellij.ui.tree.*
import com.intellij.database.view.ui.DataSourceManagerDialog
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.treeStructure.Tree
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Main panel for the CloudNativePG tool window.
 */
class CnpgToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(CnpgToolWindowPanel::class.java)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val tree: Tree
    private val treeModel: DatabaseTreeModel
    private val contextComboBox: ComboBox<String>
    private val activeConnectionsPanel: JPanel

    private val cnpgService = CnpgService.getInstance()
    private val connectionManager = ConnectionManager.getInstance()
    private val portForwardService = PortForwardService.getInstance()

    init {
        // Initialize tree
        treeModel = DatabaseTreeModel()
        tree = Tree(treeModel).apply {
            cellRenderer = DatabaseTreeCellRenderer()
            isRootVisible = false
            showsRootHandles = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            TreeUIHelper.getInstance().installTreeSpeedSearch(this)
        }

        // Context selector
        contextComboBox = ComboBox<String>().apply {
            addActionListener { refreshClusters() }
        }

        // Active connections panel
        activeConnectionsPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Active Connections")
        }

        // Setup layout
        setupLayout()
        setupToolbar()
        setupTreeListeners()
        setupConnectionListener()

        // Initial load
        loadContexts()

        // Trigger auto-reconnect of managed data sources
        // (moved from postStartupActivity which wasn't reliably running)
        triggerAutoReconnect()
    }

    private fun triggerAutoReconnect() {
        val settings = CnpgSettings.getInstance()
        if (!settings.autoReconnectOnStartup) {
            log.info("Auto-reconnect on startup is disabled")
            return
        }

        log.info("Triggering auto-reconnect from tool window initialization")

        // Immediately disable auto-sync on all managed data sources to stop any pending connection attempts
        // This runs before the delay so Database Tools doesn't try to connect with stale URLs
        val managedConnectionService = ManagedConnectionService.getInstance(project)
        managedConnectionService.disableAutoSyncOnManagedDataSources()

        scope.launch {
            try {
                // Small delay to let IDE fully initialize
                kotlinx.coroutines.delay(2000)
                managedConnectionService.reconnectManagedDataSources()
            } catch (e: Exception) {
                log.warn("Error during auto-reconnect: ${e.message}", e)
            }
        }
    }

    private fun setupLayout() {
        // Top panel with context selector
        val topPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Context:"))
            add(contextComboBox)
        }

        // Main content with splitter
        val splitter = JBSplitter(true, 0.8f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(tree)
            secondComponent = activeConnectionsPanel
        }

        val mainPanel = JPanel(BorderLayout()).apply {
            add(topPanel, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }

        setContent(mainPanel)
    }

    private fun setupToolbar() {
        val actionGroup = DefaultActionGroup().apply {
            add(RefreshAction())
            addSeparator()
            add(ConnectAction())
            add(DisconnectAction())
            addSeparator()
            add(SettingsAction())
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("CnpgToolWindow", actionGroup, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
    }

    private fun setupTreeListeners() {
        // Double-click to connect
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent
                    if (node is ClusterNode) {
                        connectToCluster(node.cluster, useReplica = false)
                    }
                }
            }
        })

        // Right-click context menu
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = showPopupIfNeeded(e)
            override fun mouseReleased(e: MouseEvent) = showPopupIfNeeded(e)

            private fun showPopupIfNeeded(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    tree.selectionPath = path

                    val node = path.lastPathComponent
                    if (node is ClusterNode) {
                        showContextMenu(e, node)
                    }
                }
            }
        })
    }

    private fun showContextMenu(e: MouseEvent, node: ClusterNode) {
        val menu = JPopupMenu()

        if (node.isConnected) {
            menu.add(JMenuItem("Disconnect").apply {
                addActionListener { disconnectFromCluster(node.cluster) }
            })
        } else {
            menu.add(JMenuItem("Connect").apply {
                addActionListener { connectToCluster(node.cluster, useReplica = false) }
            })
            menu.add(JMenuItem("Connect (Read Replica)").apply {
                addActionListener { connectToCluster(node.cluster, useReplica = true) }
            })
        }

        menu.addSeparator()
        menu.add(JMenuItem("Copy JDBC URL").apply {
            addActionListener { copyJdbcUrl(node.cluster) }
        })

        menu.show(tree, e.x, e.y)
    }

    private fun setupConnectionListener() {
        log.info("Setting up connection listener")
        connectionManager.addListener { cluster, state ->
            log.info("Connection listener received: ${cluster.displayName} -> $state")
            ApplicationManager.getApplication().invokeLater {
                when (state) {
                    is ConnectionState.Connected -> {
                        val connection = portForwardService.getConnectionForCluster(cluster)
                        log.info("Looking up connection for ${cluster.key}: found=${connection != null}, alive=${connection?.isAlive}")
                        treeModel.updateConnectionStatus(cluster, connection)
                        updateActiveConnectionsPanel()
                        showNotification(
                            "Connected to ${cluster.displayName}",
                            "Port-forward established on localhost:${state.localPort}",
                            NotificationType.INFORMATION
                        )
                    }
                    is ConnectionState.Disconnected -> {
                        treeModel.updateConnectionStatus(cluster, null)
                        updateActiveConnectionsPanel()
                    }
                    is ConnectionState.Failed -> {
                        treeModel.updateConnectionStatus(cluster, null)
                        showNotification(
                            "Failed to connect to ${cluster.displayName}",
                            state.error,
                            NotificationType.ERROR
                        )
                    }
                    is ConnectionState.Connecting -> {
                        // Could show loading indicator
                    }
                }
            }
        }
    }

    private fun loadContexts() {
        val contexts = KubernetesClientProvider.getInstance().listContexts()
        val currentContext = KubernetesClientProvider.getInstance().getCurrentContext()

        contextComboBox.removeAllItems()
        contexts.forEach { contextComboBox.addItem(it) }

        if (contexts.contains(currentContext)) {
            contextComboBox.selectedItem = currentContext
        } else if (contexts.isNotEmpty()) {
            contextComboBox.selectedIndex = 0
        }

        refreshClusters()
    }

    private fun refreshClusters() {
        val context = contextComboBox.selectedItem as? String ?: return

        treeModel.setLoading()

        scope.launch {
            try {
                val clusters = withContext(Dispatchers.IO) {
                    cnpgService.listClusters(context)
                }
                val connections = portForwardService.getActiveConnections()
                log.info("Refreshing clusters: found ${clusters.size} clusters, ${connections.size} active connections")
                connections.forEach { conn ->
                    log.info("  Active connection: ${conn.cluster.key} on port ${conn.localPort}, alive=${conn.isAlive}")
                }
                treeModel.setClusters(clusters, connections)

                // Expand all namespace nodes and update active connections panel
                ApplicationManager.getApplication().invokeLater {
                    for (i in 0 until tree.rowCount) {
                        tree.expandRow(i)
                    }
                    // Update active connections panel to show any existing connections
                    updateActiveConnectionsPanel()
                }
            } catch (e: Exception) {
                treeModel.setError("Failed to load clusters: ${e.message}")
            }
        }
    }

    private fun connectToCluster(cluster: CnpgCluster, useReplica: Boolean) {
        val context = contextComboBox.selectedItem as? String ?: return

        scope.launch {
            cnpgService.connect(project, context, cluster, useReplica)
        }
    }

    private fun disconnectFromCluster(cluster: CnpgCluster) {
        val context = contextComboBox.selectedItem as? String ?: return
        cnpgService.disconnect(project, context, cluster)
    }

    private fun copyJdbcUrl(cluster: CnpgCluster) {
        val context = contextComboBox.selectedItem as? String ?: return

        scope.launch {
            try {
                val credentials = withContext(Dispatchers.IO) {
                    cnpgService.getCredentials(cluster, context)
                }
                val connection = portForwardService.getConnectionForCluster(cluster)
                val port = connection?.localPort ?: 5432
                val jdbcUrl = credentials.getLocalJdbcUrl(port)

                val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(java.awt.datatransfer.StringSelection(jdbcUrl), null)

                showNotification(
                    "JDBC URL Copied",
                    jdbcUrl,
                    NotificationType.INFORMATION
                )
            } catch (e: Exception) {
                showNotification(
                    "Failed to copy JDBC URL",
                    e.message ?: "Unknown error",
                    NotificationType.ERROR
                )
            }
        }
    }

    private fun updateActiveConnectionsPanel() {
        activeConnectionsPanel.removeAll()

        val connections = portForwardService.getActiveConnections()
        if (connections.isEmpty()) {
            activeConnectionsPanel.add(
                JBLabel("No active connections", SwingConstants.CENTER),
                BorderLayout.CENTER
            )
        } else {
            // Use a table-like layout with GridBagLayout for better alignment
            val listPanel = JPanel(java.awt.GridBagLayout())
            val gbc = java.awt.GridBagConstraints().apply {
                fill = java.awt.GridBagConstraints.HORIZONTAL
                insets = java.awt.Insets(2, 4, 2, 4)
            }

            connections.forEachIndexed { index, conn ->
                val replicaText = if (conn.isReplica) " (RO)" else ""
                val clusterLabel = "${conn.cluster.namespace}/${conn.cluster.name}$replicaText"
                val portLabel = "localhost:${conn.localPort}"

                // Cluster name
                gbc.gridx = 0
                gbc.gridy = index
                gbc.weightx = 1.0
                gbc.anchor = java.awt.GridBagConstraints.WEST
                listPanel.add(JBLabel(clusterLabel), gbc)

                // Port
                gbc.gridx = 1
                gbc.weightx = 0.0
                listPanel.add(JBLabel(portLabel), gbc)

                // Configure button - opens Database Tools Properties dialog
                gbc.gridx = 2
                val configureBtn = JButton(AllIcons.General.Settings).apply {
                    toolTipText = "Configure data source"
                    isBorderPainted = false
                    isContentAreaFilled = false
                    addActionListener {
                        openDataSourceProperties(conn.cluster, conn.isReplica)
                    }
                }
                listPanel.add(configureBtn, gbc)

                // Disconnect button
                gbc.gridx = 3
                val disconnectBtn = JButton(AllIcons.Actions.Suspend).apply {
                    toolTipText = "Disconnect"
                    isBorderPainted = false
                    isContentAreaFilled = false
                    addActionListener {
                        cnpgService.disconnect(project, conn.context, conn.cluster)
                    }
                }
                listPanel.add(disconnectBtn, gbc)
            }

            // Add filler at bottom
            gbc.gridx = 0
            gbc.gridy = connections.size
            gbc.weighty = 1.0
            gbc.fill = java.awt.GridBagConstraints.BOTH
            listPanel.add(JPanel(), gbc)

            // Content panel with connections list and help text
            val contentPanel = JPanel(BorderLayout())
            contentPanel.add(ScrollPaneFactory.createScrollPane(listPanel), BorderLayout.CENTER)

            // Help text at the bottom
            val helpLabel = JBLabel(
                "<html><small>If you have connection problems, click the settings icon to configure the data source.</small></html>"
            ).apply {
                border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            }
            contentPanel.add(helpLabel, BorderLayout.SOUTH)

            activeConnectionsPanel.add(contentPanel, BorderLayout.CENTER)
        }

        activeConnectionsPanel.revalidate()
        activeConnectionsPanel.repaint()
    }

    /**
     * Open the Database Tools Properties dialog for a cluster's data source.
     */
    private fun openDataSourceProperties(cluster: CnpgCluster, isReplica: Boolean) {
        val databaseToolsService = DatabaseToolsService.getInstance(project)
        val dataSource = databaseToolsService.findDataSource(cluster)

        if (dataSource != null) {
            DataSourceManagerDialog.showDialog(project, dataSource, null)
        } else {
            showNotification(
                "Data Source Not Found",
                "Could not find data source for ${cluster.displayName}. Try disconnecting and reconnecting.",
                NotificationType.WARNING
            )
        }
    }

    private fun showNotification(title: String, content: String, type: NotificationType) {
        if (!CnpgSettings.getInstance().showNotifications) return

        NotificationGroupManager.getInstance()
            .getNotificationGroup("CloudNativePG Connector")
            .createNotification(title, content, type)
            .notify(project)
    }

    private fun getSelectedCluster(): CnpgCluster? {
        val path = tree.selectionPath ?: return null
        val node = path.lastPathComponent
        return if (node is ClusterNode) node.cluster else null
    }

    override fun dispose() {
        scope.cancel()
    }

    // Inner action classes
    private inner class RefreshAction : AnAction("Refresh", "Refresh clusters", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) {
            refreshClusters()
        }
    }

    private inner class ConnectAction : AnAction("Connect", "Connect to cluster", AllIcons.Actions.Execute) {
        override fun actionPerformed(e: AnActionEvent) {
            getSelectedCluster()?.let { connectToCluster(it, useReplica = false) }
        }

        override fun update(e: AnActionEvent) {
            val cluster = getSelectedCluster()
            e.presentation.isEnabled = cluster != null && !portForwardService.isConnected(cluster)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class DisconnectAction : AnAction("Disconnect", "Disconnect from cluster", AllIcons.Actions.Suspend) {
        override fun actionPerformed(e: AnActionEvent) {
            getSelectedCluster()?.let { disconnectFromCluster(it) }
        }

        override fun update(e: AnActionEvent) {
            val cluster = getSelectedCluster()
            e.presentation.isEnabled = cluster != null && portForwardService.isConnected(cluster)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class SettingsAction : AnAction("Settings", "Open settings", AllIcons.General.Settings) {
        override fun actionPerformed(e: AnActionEvent) {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, "CloudNativePG Connector")
        }
    }
}
