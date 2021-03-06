package net.termer.twine.rtfl.utils

import de.mkammerer.argon2.Argon2
import de.mkammerer.argon2.Argon2Factory
import de.mkammerer.argon2.Argon2Factory.Argon2Types
import io.vertx.kotlin.coroutines.await
import net.termer.twine.ServerManager.vertx

/**
 * Utility class for dealing with hashing and other cryptographic tasks
 * @since 1.0
 */
class Crypt {
    // Argon2 instance
    private val argon2 : Argon2 = Argon2Factory.create(Argon2Types.ARGON2id)

    /**
     * Hashes (and salts) the provided password
     * @since 1.0
     */
    suspend fun hashPassword(password : String) : String? {
        return vertx().executeBlocking<String> {
            // Hash password using configured performance settings
            it.complete(argon2.hash(1, 1024, 2, password.toCharArray()))
        }.await()
    }

    /**
     * Checks if the provided password matches the specified hash
     * @since 1.0
     */
    suspend fun verifyPassword(password : String, hash : String) : Boolean? {
        return vertx().executeBlocking<Boolean> {
            it.complete(argon2.verify(hash, password.toCharArray()))
        }.await()
    }
}