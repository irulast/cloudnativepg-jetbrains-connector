package com.irulast.cloudnativepg.intellij.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*

/**
 * Settings configurable for CloudNativePG Connector.
 */
class CnpgSettingsConfigurable : BoundConfigurable("CloudNativePG Connector") {

    private val settings = CnpgSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group("Port Forwarding") {
            row("Local port range:") {
                intTextField(1024..65535)
                    .bindIntText(settings::portRangeStart)
                    .gap(RightGap.SMALL)
                label("-")
                    .gap(RightGap.SMALL)
                intTextField(1024..65535)
                    .bindIntText(settings::portRangeEnd)
            }.comment("Port range for local port-forwarding (default: 15432-15532)")

            row {
                checkBox("Show notifications on connection events")
                    .bindSelected(settings::showNotifications)
            }
        }

        group("Data Source Naming") {
            row("Naming pattern:") {
                comboBox(CnpgSettings.NAMING_PATTERNS.map { it.first })
                    .bindItem(
                        { settings.dataSourceNamingPattern },
                        { settings.dataSourceNamingPattern = it ?: CnpgSettings.DEFAULT_NAMING_PATTERN }
                    )
                    .comment("Available variables: \${context}, \${namespace}, \${name}")
            }

            row {
                checkBox("Organize in folders (by context/namespace)")
                    .bindSelected(settings::useFolderOrganization)
            }

            row {
                checkBox("Mark replica connections as read-only")
                    .bindSelected(settings::markReplicaReadOnly)
            }
        }
    }
}
