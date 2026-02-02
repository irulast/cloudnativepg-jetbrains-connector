package com.irulast.cloudnativepg.intellij.ui.tree

import com.irulast.cloudnativepg.intellij.models.ActiveConnection
import com.irulast.cloudnativepg.intellij.models.CnpgCluster
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode

/**
 * Tree model for displaying CloudNativePG clusters grouped by namespace.
 */
class DatabaseTreeModel : DefaultTreeModel(RootNode()) {

    private val rootNode: RootNode
        get() = root as RootNode

    /**
     * Set the clusters to display.
     *
     * @param clusters List of clusters to display.
     * @param connections Currently active connections.
     */
    fun setClusters(clusters: List<CnpgCluster>, connections: List<ActiveConnection> = emptyList()) {
        rootNode.removeAllChildren()

        if (clusters.isEmpty()) {
            rootNode.add(EmptyNode())
        } else {
            // Group clusters by namespace
            val groupedClusters = clusters.groupBy { it.namespace }
                .toSortedMap()

            groupedClusters.forEach { (namespace, namespaceClusters) ->
                val namespaceNode = NamespaceNode(namespace)

                namespaceClusters.sortedBy { it.name }.forEach { cluster ->
                    val connection = connections.find { it.cluster.key == cluster.key }
                    val clusterNode = ClusterNode(cluster, connection)
                    namespaceNode.add(clusterNode)
                }

                rootNode.add(namespaceNode)
            }
        }

        reload()
    }

    /**
     * Show loading state.
     */
    fun setLoading() {
        rootNode.removeAllChildren()
        rootNode.add(LoadingNode())
        reload()
    }

    /**
     * Show error state.
     */
    fun setError(message: String) {
        rootNode.removeAllChildren()
        rootNode.add(ErrorNode(message))
        reload()
    }

    /**
     * Update connection status for a cluster.
     */
    fun updateConnectionStatus(cluster: CnpgCluster, connection: ActiveConnection?) {
        findClusterNode(cluster)?.let { node ->
            node.connection = connection
            nodeChanged(node)
        }
    }

    /**
     * Find the node for a cluster.
     */
    fun findClusterNode(cluster: CnpgCluster): ClusterNode? {
        for (i in 0 until rootNode.childCount) {
            val namespaceNode = rootNode.getChildAt(i)
            if (namespaceNode is NamespaceNode) {
                for (j in 0 until namespaceNode.childCount) {
                    val clusterNode = namespaceNode.getChildAt(j)
                    if (clusterNode is ClusterNode && clusterNode.cluster.key == cluster.key) {
                        return clusterNode
                    }
                }
            }
        }
        return null
    }

    /**
     * Get all cluster nodes.
     */
    fun getAllClusterNodes(): List<ClusterNode> {
        val nodes = mutableListOf<ClusterNode>()
        for (i in 0 until rootNode.childCount) {
            val namespaceNode = rootNode.getChildAt(i)
            if (namespaceNode is NamespaceNode) {
                for (j in 0 until namespaceNode.childCount) {
                    val clusterNode = namespaceNode.getChildAt(j)
                    if (clusterNode is ClusterNode) {
                        nodes.add(clusterNode)
                    }
                }
            }
        }
        return nodes
    }
}
