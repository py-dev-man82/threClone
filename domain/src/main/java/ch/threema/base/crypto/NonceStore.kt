/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2024-2025 Threema GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.threema.base.crypto

import ch.threema.base.utils.LoggingUtil
import ch.threema.base.utils.SecureRandomUtil
import com.neilalexander.jnacl.NaCl
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import kotlin.jvm.Throws

private val logger = LoggingUtil.getThreemaLogger("NonceStore")

enum class NonceScope {
    CSP,
    D2D,
}

interface NonceStore {
    fun exists(scope: NonceScope, nonce: Nonce): Boolean
    fun store(scope: NonceScope, nonce: Nonce): Boolean
    fun getCount(scope: NonceScope): Long
    fun getAllHashedNonces(scope: NonceScope): List<HashedNonce>

    /**
     * Add a chunk of hashed nonces in their byte array representation to a list.
     *
     * @param scope The scope of which nonces should be used
     * @param chunkSize The number of nonces to add
     * @param offset the offset where reading the nonces starts
     * @param hashedNonces The list to which the nonces should be added
     */
    fun addHashedNoncesChunk(
        scope: NonceScope,
        chunkSize: Int,
        offset: Int,
        hashedNonces: MutableList<HashedNonce>,
    )

    fun insertHashedNonces(scope: NonceScope, nonces: List<HashedNonce>): Boolean
}

fun interface NonceFactoryNonceBytesProvider {
    fun next(length: Int): ByteArray
}

class NonceFactory(
    private val nonceStore: NonceStore,
    // Nonce Provider is injectable for testing purposes
    private val nonceProvider: NonceFactoryNonceBytesProvider,
) {
    constructor(nonceStore: NonceStore) :
        this(nonceStore, { length -> SecureRandomUtil.generateRandomBytes(length) })

    @JvmName("nextNonce")
    fun next(scope: NonceScope): Nonce {
        return sequence {
            while (true) {
                val nonce = Nonce(nonceProvider.next(NaCl.NONCEBYTES))
                yield(nonce)
            }
        }
            .first { !exists(scope, it) }
    }

    /**
     * @return true if the nonce has been stored, false if the nonce could not be stored or already existed.
     */
    @JvmName("storeNonce")
    fun store(scope: NonceScope, nonce: Nonce) = nonceStore.store(scope, nonce)

    @JvmName("existsNonce")
    fun exists(scope: NonceScope, nonce: Nonce) = nonceStore.exists(scope, nonce)

    fun getCount(scope: NonceScope): Long = nonceStore.getCount(scope)

    fun getAllHashedNonces(scope: NonceScope) = nonceStore.getAllHashedNonces(scope)

    /**
     * @see NonceStore.addHashedNoncesChunk
     */
    fun addHashedNoncesChunk(
        scope: NonceScope,
        chunkSize: Int,
        offset: Int,
        nonces: MutableList<HashedNonce>,
    ) {
        nonceStore.addHashedNoncesChunk(scope, chunkSize, offset, nonces)
    }

    fun insertHashedNonces(scope: NonceScope, nonces: List<HashedNonce>) =
        nonceStore.insertHashedNonces(scope, nonces)

    /**
     * Insert the hashed nonces. Note that byte arrays of length [NaCl.NONCEBYTES] will be hashed with HMAC-SHA256 using the [identity] as the key
     * before being inserted if [identity] is not null. Nonces with a different length than 32 or [NaCl.NONCEBYTES] bytes will be discarded.
     *
     * @throws NoSuchAlgorithmException if the algorithm is not available on the device
     * @throws InvalidKeyException if the [identity] is not null and not suitable as key
     *
     * @return true if all nonces were inserted successfully and false if at least one nonce was skipped
     */
    @Throws(NoSuchAlgorithmException::class, InvalidKeyException::class)
    fun insertHashedNoncesJava(scope: NonceScope, nonces: List<ByteArray>, identity: String?): Boolean {
        val sanitizedNonceHashes = nonces.toHashedNonces(identity)
        insertHashedNonces(scope, sanitizedNonceHashes.filterNotNull())
        return !sanitizedNonceHashes.contains(null)
    }

    /**
     * Converts the byte arrays to hashed nonces. If the length already matches 32 bytes, then we assume that it is already a hashed nonce. If the
     * length is [NaCl.NONCEBYTES], we first hash it if [identity] is not null.
     *
     * @throws NoSuchAlgorithmException if the algorithm is not available on the device
     * @throws InvalidKeyException if the [identity] is not null and not suitable as key
     */
    private fun List<ByteArray>.toHashedNonces(identity: String?): List<HashedNonce?> = map { nonceBytes ->
        when (nonceBytes.size) {
            32 -> HashedNonce(nonceBytes)
            NaCl.NONCEBYTES -> {
                if (identity != null) {
                    Nonce(nonceBytes).hashNonce(identity)
                } else {
                    logger.warn("Cannot hash nonce because no identity is provided")
                    null
                }
            }

            else -> {
                logger.warn("Cannot insert invalid nonce of length {}", nonceBytes.size)
                null
            }
        }
    }
}
