package com.irulast.cloudnativepg.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action to copy JDBC URL to clipboard.
 */
class CopyJdbcUrlAction : AnAction("Copy JDBC URL", "Copy JDBC URL to clipboard", null) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        // Copy is handled by the tool window panel context menu
        // This action is registered for menu/shortcut use
    }
}
