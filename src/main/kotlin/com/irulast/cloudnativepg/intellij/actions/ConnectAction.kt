package com.irulast.cloudnativepg.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action to connect to a CloudNativePG cluster (primary).
 */
class ConnectAction : AnAction("Connect", "Connect to the cluster", null) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        // Connection is handled by the tool window panel context menu
        // This action is registered for menu/shortcut use
    }
}
