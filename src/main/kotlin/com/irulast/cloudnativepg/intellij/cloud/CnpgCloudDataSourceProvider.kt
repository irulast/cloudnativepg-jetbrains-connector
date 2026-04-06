package com.irulast.cloudnativepg.intellij.cloud

import com.intellij.database.cloud.explorer.CloudConnectionData
import com.intellij.database.cloud.explorer.CloudConnectionUI
import com.intellij.database.cloud.explorer.CloudDataSourceProvider
import com.intellij.database.cloud.explorer.RemoteDataSourceProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import kotlinx.coroutines.CoroutineScope
import java.util.function.Consumer
import javax.swing.Icon

/**
 * Registers CloudNativePG as a cloud data source provider in the Database tool window.
 * Users can discover and import CloudNativePG clusters via:
 *   Database tool window -> + -> Import from Cloud -> CloudNativePG
 */
class CnpgCloudDataSourceProvider : CloudDataSourceProvider {

    override fun getDisplayName(): String = "CloudNativePG (Kubernetes)"

    override fun getIcon(): Icon = IconLoader.getIcon("/icons/cnpg.svg", CnpgCloudDataSourceProvider::class.java)

    override fun createConnectionData(): CloudConnectionData = CnpgCloudConnectionData()

    override fun createDataSourceProvider(
        connectionData: CloudConnectionData,
        scope: CoroutineScope,
        errorCallback: Consumer<Exception>
    ): RemoteDataSourceProvider {
        return CnpgRemoteDataSourceProvider(connectionData, scope, errorCallback)
    }

    override fun createUI(
        project: Project,
        connectionData: CloudConnectionData,
        parentDisposable: Disposable,
        scope: CoroutineScope
    ): CloudConnectionUI {
        return CnpgCloudConnectionUI(project, connectionData, scope)
    }
}
