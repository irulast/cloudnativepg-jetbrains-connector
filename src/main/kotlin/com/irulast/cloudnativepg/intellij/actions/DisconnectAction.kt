package com.irulast.cloudnativepg.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action to disconnect from a CloudNativePG cluster.
 */
class DisconnectAction : AnAction("Disconnect", "Disconnect from the cluster", null) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        // Disconnection is handled by the tool window panel
        // This action is registered for menu/shortcut use
    }
}
