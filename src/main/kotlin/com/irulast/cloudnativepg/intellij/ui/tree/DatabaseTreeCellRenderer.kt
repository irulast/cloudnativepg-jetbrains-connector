package com.irulast.cloudnativepg.intellij.ui.tree

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree
import javax.swing.Icon

/**
 * Custom cell renderer for the cluster tree.
 */
class DatabaseTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        when (value) {
            is RootNode -> renderRoot(value)
            is NamespaceNode -> renderNamespace(value, expanded)
            is ClusterNode -> renderCluster(value)
            is LoadingNode -> renderLoading()
            is ErrorNode -> renderError(value)
            is EmptyNode -> renderEmpty(value)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun renderRoot(node: RootNode) {
        icon = AllIcons.Nodes.DataSchema
        append("CloudNativePG Clusters", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
    }

    private fun renderNamespace(node: NamespaceNode, expanded: Boolean) {
        icon = if (expanded) AllIcons.Nodes.Folder else AllIcons.Nodes.Folder
        append(node.namespace, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
    }

    private fun renderCluster(node: ClusterNode) {
        val cluster = node.cluster

        // Icon based on connection status
        icon = when {
            node.isConnected -> AllIcons.RunConfigurations.TestPassed  // Green checkmark for connected
            cluster.isHealthy -> AllIcons.Nodes.DataTables
            else -> AllIcons.Nodes.ExceptionClass
        }

        // Cluster name
        val nameAttributes = if (node.isConnected) {
            SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
        } else {
            SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
        append(cluster.name, nameAttributes)

        // Status info
        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)

        // Instance count
        val instancesText = "${cluster.readyInstances}/${cluster.instances}"
        val instancesAttr = if (cluster.readyInstances < cluster.instances) {
            SimpleTextAttributes.ERROR_ATTRIBUTES
        } else {
            SimpleTextAttributes.GRAYED_ATTRIBUTES
        }
        append(instancesText, instancesAttr)

        // Version
        cluster.version?.let { version ->
            append(" v$version", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        // Connection status
        if (node.isConnected) {
            node.connection?.let { conn ->
                append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val replicaText = if (conn.isReplica) " (RO)" else ""
                append("localhost:${conn.localPort}$replicaText", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }
        }

        // Health indicator for unhealthy clusters
        if (!cluster.isHealthy) {
            append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append(cluster.phase, SimpleTextAttributes.ERROR_ATTRIBUTES)
        }
    }

    private fun renderLoading() {
        icon = AllIcons.Process.Step_1
        append("Loading clusters...", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
    }

    private fun renderError(node: ErrorNode) {
        icon = AllIcons.General.Error
        append(node.message, SimpleTextAttributes.ERROR_ATTRIBUTES)
    }

    private fun renderEmpty(node: EmptyNode) {
        icon = AllIcons.General.Information
        append(node.userObject.toString(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}
