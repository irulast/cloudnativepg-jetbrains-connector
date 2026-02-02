package com.irulast.cloudnativepg.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action to connect to a CloudNativePG cluster read replica.
 */
class ConnectReplicaAction : AnAction(
    "Connect (Read Replica)",
    "Connect to a read-only replica",
    null
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        // Connection is handled by the tool window panel context menu
        // This action is registered for menu/shortcut use
    }
}
