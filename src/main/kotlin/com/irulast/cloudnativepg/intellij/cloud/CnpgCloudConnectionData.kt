package com.irulast.cloudnativepg.intellij.cloud

import com.intellij.database.cloud.explorer.CloudConnectionData
import com.intellij.openapi.util.IconLoader
import org.jdom.Element
import java.util.UUID
import javax.swing.Icon

/**
 * Persisted configuration for a CloudNativePG cloud connection.
 * Stores the Kubernetes context and optional namespace filter.
 */
class CnpgCloudConnectionData(
    var context: String = "",
    var namespaceFilter: String = ""
) : CloudConnectionData {

    override val cloudProviderId: String = PROVIDER_ID

    override val id: String = UUID.randomUUID().toString()
    private var _id: String = id

    override var name: String = "CloudNativePG"

    override var comment: String? = ""

    override val icon: Icon
        get() = IconLoader.getIcon("/icons/cnpg.svg", CnpgCloudConnectionData::class.java)

    override fun load(element: Element): CloudConnectionData {
        _id = element.getAttributeValue("id") ?: UUID.randomUUID().toString()
        name = element.getAttributeValue("name") ?: "CloudNativePG"
        comment = element.getAttributeValue("comment")
        context = element.getAttributeValue("context") ?: ""
        namespaceFilter = element.getAttributeValue("namespaceFilter") ?: ""
        return this
    }

    override fun save(element: Element): Element {
        element.setAttribute("id", _id)
        element.setAttribute("name", name)
        element.setAttribute("comment", comment)
        element.setAttribute("context", context)
        element.setAttribute("namespaceFilter", namespaceFilter)
        return element
    }

    override fun copy(): CloudConnectionData {
        return CnpgCloudConnectionData(
            context = context,
            namespaceFilter = namespaceFilter
        ).also {
            it.name = name
            it.comment = comment
        }
    }

    override fun equalsConfiguration(other: CloudConnectionData): Boolean {
        if (other !is CnpgCloudConnectionData) return false
        return context == other.context && namespaceFilter == other.namespaceFilter
    }

    companion object {
        const val PROVIDER_ID = "cnpg"
    }
}
