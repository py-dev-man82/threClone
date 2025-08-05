/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2023-2025 Threema GmbH
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

package ch.threema.base.utils

import com.google.protobuf.ByteString
import java.security.SecureRandom

object SecureRandomUtil {
    private val secureRandom: SecureRandom by lazy { SecureRandom() }

    /**
     * Creates between 0 (inclusive) and 256 (exclusive) bytes of PKCS#7 style padding.
     * Each byte of the padding has the length of the padding as value.
     */
    private fun generateRandomPadding(): ByteArray {
        val paddingLength = secureRandom.nextInt(256)
        val paddingValue = paddingLength.toByte()
        return ByteArray(paddingLength) { paddingValue }
    }

    /**
     * Create random padding as described in [generateRandomPadding] and wraps
     * it in a protobuf [ByteString].
     */
    fun generateRandomProtobufPadding(): ByteString {
        return ByteString.copyFrom(generateRandomPadding())
    }

    /**
     * @return [length] random bytes.
     */
    fun generateRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    fun generateRandomU64(): ULong {
        return secureRandom.nextLong().toULong()
    }
}
