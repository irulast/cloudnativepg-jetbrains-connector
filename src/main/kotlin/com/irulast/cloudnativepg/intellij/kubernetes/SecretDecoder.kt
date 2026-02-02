package com.irulast.cloudnativepg.intellij.kubernetes

import com.irulast.cloudnativepg.intellij.models.DatabaseCredentials
import com.irulast.cloudnativepg.intellij.models.SecretKeys
import io.fabric8.kubernetes.api.model.Secret
import java.util.Base64

/**
 * Utility for decoding Kubernetes secrets.
 */
object SecretDecoder {

    /**
     * Decode a base64-encoded value from a secret.
     */
    fun decodeValue(encoded: String): String {
        return String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }

    /**
     * Get a decoded value from a secret's data map.
     */
    fun getValue(secret: Secret, key: String): String? {
        return secret.data?.get(key)?.let { decodeValue(it) }
    }

    /**
     * Extract database credentials from a CloudNativePG app secret.
     *
     * CloudNativePG creates a secret named `<cluster>-app` with:
     * - dbname: database name
     * - user/username: username
     * - password: password
     * - host: service hostname
     * - port: port (5432)
     * - jdbc-uri: JDBC URL
     * - uri: PostgreSQL URI
     * - pgpass: .pgpass format line
     */
    fun extractCredentials(secret: Secret): DatabaseCredentials {
        if (secret.data == null) throw IllegalArgumentException("Secret has no data")

        val database = getValue(secret, SecretKeys.DBNAME)
            ?: throw IllegalArgumentException("Secret missing '${SecretKeys.DBNAME}' key")

        val username = getValue(secret, SecretKeys.USERNAME)
            ?: getValue(secret, SecretKeys.USER)
            ?: throw IllegalArgumentException("Secret missing username key")

        val password = getValue(secret, SecretKeys.PASSWORD)
            ?: throw IllegalArgumentException("Secret missing '${SecretKeys.PASSWORD}' key")

        val host = getValue(secret, SecretKeys.HOST)
            ?: throw IllegalArgumentException("Secret missing '${SecretKeys.HOST}' key")

        val port = getValue(secret, SecretKeys.PORT)?.toIntOrNull() ?: 5432

        val jdbcUrl = getValue(secret, SecretKeys.JDBC_URI)

        return DatabaseCredentials(
            database = database,
            username = username,
            password = password,
            host = host,
            port = port,
            jdbcUrl = jdbcUrl
        )
    }
}
