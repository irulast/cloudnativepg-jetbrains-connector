package com.irulast.cloudnativepg.intellij.models

/**
 * Database credentials extracted from a Kubernetes secret.
 */
data class DatabaseCredentials(
    val database: String,
    val username: String,
    val password: String,
    val host: String,
    val port: Int = 5432,
    val jdbcUrl: String?
) {
    /**
     * Generate a JDBC URL for connecting via localhost port-forward.
     */
    fun getLocalJdbcUrl(localPort: Int): String {
        return "jdbc:postgresql://localhost:$localPort/$database"
    }

    /**
     * Generate a PostgreSQL connection URI for localhost.
     */
    fun getLocalUri(localPort: Int): String {
        return "postgresql://$username@localhost:$localPort/$database"
    }
}

/**
 * Secret keys used by CloudNativePG.
 */
object SecretKeys {
    const val DBNAME = "dbname"
    const val USER = "user"
    const val USERNAME = "username"
    const val PASSWORD = "password"
    const val HOST = "host"
    const val PORT = "port"
    const val JDBC_URI = "jdbc-uri"
    const val URI = "uri"
    const val PGPASS = "pgpass"
}
