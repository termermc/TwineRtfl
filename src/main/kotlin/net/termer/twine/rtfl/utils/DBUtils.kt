package net.termer.twine.rtfl.utils

import io.vertx.kotlin.coroutines.await
import io.vertx.pgclient.PgConnectOptions
import io.vertx.pgclient.PgPool
import io.vertx.sqlclient.PoolOptions
import net.termer.twine.ServerManager.vertx
import net.termer.twine.rtfl.Module.Companion.dbConnections

/**
 * Creates a new database connection or retrieves an existing one if it matches the same address+port+database combination.
 * @param address The DB address
 * @param port The DB port
 * @param database The DB name
 * @param username The username to login with
 * @param password The password to login with
 * @param maxPoolSize The maximum connection pool size
 * @return The SQLClient for these credentials
 * @since 1.1.0
 */
suspend fun createClient(address: String, port: Int, database: String, username: String, password: String, maxPoolSize: Int): PgPool {
    val url = "$address:$port/$database"

    return if(dbConnections.containsKey(url)) {
        dbConnections[url]!!
    } else {
        // Configuration
        val connOps = PgConnectOptions()
                .setHost(address)
                .setPort(port)
                .setDatabase(database)
                .setUser(username)
                .setPassword(password)
        val poolOps = PoolOptions()
                .setMaxSize(maxPoolSize)

        val pool = PgPool.pool(vertx(), connOps, poolOps)
        dbConnections[url] = pool

        // Test connection
        pool.connection.await().query("SELECT 1").execute().await()

        pool
    }
}

/**
 * Closes a JDBC client connection
 * @param url The URL of the connection to close
 * @since 1.0.0
 */
suspend fun closeClient(url: String) {
    val client = dbConnections[url]
    client?.close()?.await()
    dbConnections.remove(url)
}