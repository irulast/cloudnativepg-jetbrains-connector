package com.irulast.cloudnativepg.intellij.actions

import com.irulast.cloudnativepg.intellij.services.CnpgService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action to refresh the cluster list.
 */
class RefreshAction : AnAction(
    "Refresh",
    "Refresh CloudNativePG clusters",
    AllIcons.Actions.Refresh
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        // Refresh is handled by the tool window panel
        // This action is registered for toolbar use
        CnpgService.getInstance().refreshConfig()
    }
}
