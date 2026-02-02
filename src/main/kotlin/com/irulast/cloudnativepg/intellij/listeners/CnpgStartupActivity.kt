package com.irulast.cloudnativepg.intellij.listeners

import com.irulast.cloudnativepg.intellij.services.ManagedConnectionService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Startup activity that initializes CloudNativePG connection management
 * when a project is opened.
 */
class CnpgStartupActivity : ProjectActivity {

    private val log = Logger.getInstance(CnpgStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        log.info("CloudNativePG plugin initializing for project: ${project.name}")

        try {
            val service = ManagedConnectionService.getInstance(project)

            // Schedule initialization to run on a pooled thread to avoid blocking startup
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    service.initialize()
                    log.info("CloudNativePG connection management initialized for project: ${project.name}")
                } catch (e: Exception) {
                    log.error("Failed to initialize CloudNativePG connection management", e)
                }
            }
        } catch (e: Exception) {
            log.error("Error in CnpgStartupActivity", e)
        }
    }
}