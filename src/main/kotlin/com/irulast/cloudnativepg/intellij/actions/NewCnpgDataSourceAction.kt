package com.irulast.cloudnativepg.intellij.actions

import com.irulast.cloudnativepg.intellij.ui.CnpgDataSourceDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Action to create a new data source from a CloudNativePG cluster.
 * This action appears in the Database window's "New" menu.
 */
class NewCnpgDataSourceAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val dialog = CnpgDataSourceDialog(project)
        if (dialog.showAndGet()) {
            // Dialog handles the connection and data source creation
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
