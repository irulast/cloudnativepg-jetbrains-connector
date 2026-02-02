package com.irulast.cloudnativepg.intellij.ui.tree

import com.irulast.cloudnativepg.intellij.models.ActiveConnection
import com.irulast.cloudnativepg.intellij.models.CnpgCluster
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Root node for the cluster tree.
 */
class RootNode : DefaultMutableTreeNode("CloudNativePG Clusters")

/**
 * Node representing a Kubernetes namespace.
 */
class NamespaceNode(val namespace: String) : DefaultMutableTreeNode(namespace) {
    override fun toString(): String = namespace
}

/**
 * Node representing a CloudNativePG cluster.
 */
class ClusterNode(
    val cluster: CnpgCluster,
    var connection: ActiveConnection? = null
) : DefaultMutableTreeNode(cluster) {

    val isConnected: Boolean
        get() = connection?.isAlive == true

    override fun toString(): String = cluster.name
}

/**
 * Node shown when loading clusters.
 */
class LoadingNode : DefaultMutableTreeNode("Loading...")

/**
 * Node shown when an error occurred.
 */
class ErrorNode(val message: String) : DefaultMutableTreeNode(message)

/**
 * Node shown when no clusters are found.
 */
class EmptyNode(message: String = "No clusters found") : DefaultMutableTreeNode(message)
