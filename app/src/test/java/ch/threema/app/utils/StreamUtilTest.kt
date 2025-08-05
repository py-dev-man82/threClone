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

package ch.threema.app.utils

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamUtilTest {
    @Test
    fun testEqual() {
        val bytes = byteArrayOf(0, 1, 2, 3)
        val inputStream = ByteArrayInputStream(bytes)
        assertTrue(inputStream.contentEquals(bytes))
    }

    @Test
    fun testDifferentLength() {
        val bytes = byteArrayOf(0, 1, 2, 3)

        // Assert false when the provided byte array is longer
        assertFalse(ByteArrayInputStream(bytes).contentEquals(bytes + 11))

        // Assert false when the provided byte array is shorter
        assertFalse(ByteArrayInputStream(bytes).contentEquals(bytes.copyOf(bytes.size - 2)))
    }

    @Test
    fun testDifferentContent() {
        val inputBytes = byteArrayOf(0, 1, 2, 3)

        assertFalse(ByteArrayInputStream(inputBytes).contentEquals(byteArrayOf(0, 1, 2, 42)))
        assertFalse(ByteArrayInputStream(inputBytes).contentEquals(byteArrayOf(42, 1, 2, 3)))
        assertFalse(ByteArrayInputStream(inputBytes).contentEquals(byteArrayOf(42, 42, 42, 42)))
        assertFalse(ByteArrayInputStream(inputBytes).contentEquals(byteArrayOf(0, 42, 3, 4)))
    }

    @Test
    fun testStreamNull() {
        val inputStream: InputStream? = null

        // Assert that the comparison fails when the input is not null
        assertFalse(inputStream.contentEquals(byteArrayOf(0, 1, 2, 3)))
        assertFalse(inputStream.contentEquals(byteArrayOf()))

        // Assert that the comparison succeeds when the byte array is also null
        assertTrue(inputStream.contentEquals(null))
    }

    @Test
    fun testBytesNull() {
        val inputStream = ByteArrayInputStream(byteArrayOf(0, 1, 2, 3))

        // Assert that the comparison fails when the input is null
        assertFalse(inputStream.contentEquals(null))
    }

    @Test
    fun testStreamEmpty() {
        // Assert that the comparison fails when the input is not empty or null
        assertFalse(ByteArrayInputStream(byteArrayOf()).contentEquals(byteArrayOf(0)))
        assertFalse(ByteArrayInputStream(byteArrayOf()).contentEquals(null))

        // Assert that the comparison succeeds when both the input and the provided bytes are empty
        assertTrue(ByteArrayInputStream(byteArrayOf()).contentEquals(byteArrayOf()))
    }
}
