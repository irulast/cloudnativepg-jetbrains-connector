package com.irulast.cloudnativepg.intellij.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

/**
 * Action to open CloudNativePG Connector settings.
 */
class OpenSettingsAction : AnAction(
    "Settings",
    "Open CloudNativePG Connector settings",
    AllIcons.General.Settings
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "CloudNativePG Connector")
    }
}
