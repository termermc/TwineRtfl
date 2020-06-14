package net.termer.twine.rtfl.utils

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.ext.sql.SQLClient
import io.vertx.kotlin.core.executeBlockingAwait
import io.vertx.kotlin.coroutines.awaitBlocking
import net.termer.twine.ServerManager.vertx
import net.termer.twine.rtfl.Module.Companion.dbConnections

/**
 * Creates a new database connection or retrieves an existing one if it matches the same URL.
 * URL format: jdbc:<postgresql|mysql>://<address>:<port>/<database>
 * Example: jdbc:postgresql://localhost:5432/mydb
 * @param url The JDBC database URL
 * @param username The username to login with
 * @param password The password to login with
 * @param maxPoolSize The maximum connection pool size
 * @return The SQLClient for these credentials
 * @since 1.0
 */
suspend fun createClient(url: String, username: String, password: String, maxPoolSize: Int): SQLClient {
    return if(dbConnections.containsKey(url)) {
        dbConnections[url]!!
    } else {
        val cfg = HikariConfig()

        // Set properties
        cfg.jdbcUrl = url
        cfg.username = username
        cfg.password = password
        cfg.maximumPoolSize = maxPoolSize

        val client = vertx().executeBlockingAwait<SQLClient> {
            it.complete(JDBCClient.create(vertx(), HikariDataSource(cfg)))
        }!!
        dbConnections[url] = client

        client
    }
}

/**
 * Closes a JDBC client connection
 * @param url The URL of the connection to close
 * @since 1.0
 */
suspend fun closeClient(url: String) {
    val client = dbConnections[url]
    vertx().executeBlockingAwait<Unit> {
        client?.close()
    }
    dbConnections.remove(url)
}