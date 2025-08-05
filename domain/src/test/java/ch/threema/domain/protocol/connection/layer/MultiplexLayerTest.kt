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

package ch.threema.domain.protocol.connection.layer

import ch.threema.domain.protocol.connection.InputPipe
import ch.threema.domain.protocol.connection.PipeHandler
import ch.threema.domain.protocol.connection.ServerConnectionDispatcher
import ch.threema.domain.protocol.connection.csp.CspSessionState
import ch.threema.domain.protocol.connection.data.CspData
import ch.threema.domain.protocol.connection.data.CspFrame
import ch.threema.domain.protocol.connection.data.CspLoginMessage
import ch.threema.domain.protocol.connection.data.InboundL1Message
import ch.threema.domain.protocol.connection.data.InboundL2Message
import ch.threema.domain.protocol.connection.data.OutboundL2Message
import ch.threema.domain.protocol.connection.data.OutboundL3Message
import ch.threema.domain.protocol.connection.socket.ServerSocketCloseReason
import ch.threema.domain.protocol.connection.util.ServerConnectionController
import ch.threema.domain.protocol.csp.ProtocolDefines
import io.mockk.every
import io.mockk.mockk
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiplexLayerTest {
    @Test
    fun `outbound packages exceeding the maximum allowed size must be ignored`() {
        // Arrange
        val controller = mockController()

        val layer = MultiplexLayer(controller)

        val source = InputPipe<OutboundL3Message, Unit>()
        val handler = PipeHandlerHelper<OutboundL2Message>()

        source.pipeThrough(layer.encoder).setHandler(handler)

        // Act
        // Maximum allowed frame size is 8192
        source.send(CspFrame(ByteArray(8193)))

        // Assert
        assertEquals(0, handler.messages.size)
    }

    @Test
    fun `outbound CspLoginMessages must be processed`() {
        // Arrange
        val controller = mockController()

        val layer = MultiplexLayer(controller)

        val source = InputPipe<OutboundL3Message, Unit>()
        val handler = PipeHandlerHelper<OutboundL2Message>()

        source.pipeThrough(layer.encoder).setHandler(handler)

        // Act & Assert

        source.send(CspLoginMessage(ByteArray(1)))
        source.send(CspLoginMessage(ByteArray(2)))
        source.send(CspLoginMessage(ByteArray(4)))
        source.send(CspLoginMessage(ByteArray(8)))
        source.send(CspLoginMessage(ByteArray(16)))
        source.send(CspLoginMessage(ByteArray(32)))
        source.send(CspLoginMessage(ByteArray(64)))
        source.send(CspLoginMessage(ByteArray(128)))
        source.send(CspLoginMessage(ByteArray(256)))
        source.send(CspLoginMessage(ByteArray(512)))
        source.send(CspLoginMessage(ByteArray(1024)))
        source.send(CspLoginMessage(ByteArray(2048)))
        source.send(CspLoginMessage(ByteArray(4096)))
        source.send(CspLoginMessage(ByteArray(8192)))

        // Assert
        assertEquals(14, handler.messages.size)
        handler.messages.forEachIndexed { idx, msg ->
            assertTrue(msg is CspData)
            assertEquals(2.toDouble().pow(idx).toInt(), msg.bytes.size)
        }
    }

    @Test
    fun `outbound CspFrames must be prepended with the size`() {
        // Arrange
        val controller = mockController()

        val layer = MultiplexLayer(controller)

        val source = InputPipe<OutboundL3Message, Unit>()
        val handler = PipeHandlerHelper<OutboundL2Message>()

        source.pipeThrough(layer.encoder).setHandler(handler)

        // Act & Assert
        source.send(CspFrame(ByteArray(1)))
        source.send(CspFrame(ByteArray(2)))
        source.send(CspFrame(ByteArray(4)))
        source.send(CspFrame(ByteArray(8)))
        source.send(CspFrame(ByteArray(16)))
        source.send(CspFrame(ByteArray(32)))
        source.send(CspFrame(ByteArray(64)))
        source.send(CspFrame(ByteArray(128)))
        source.send(CspFrame(ByteArray(256)))
        source.send(CspFrame(ByteArray(512)))
        source.send(CspFrame(ByteArray(1024)))
        source.send(CspFrame(ByteArray(2048)))
        source.send(CspFrame(ByteArray(4096)))
        // Maximum allowed frame size is 8192 bytes
        source.send(CspFrame(ByteArray(8192)))

        // Assert
        assertEquals(14, handler.messages.size)
        handler.messages.forEachIndexed { idx, msg ->
            assertTrue(msg is CspData)
            val expectedDataLength = 2.toDouble().pow(idx).toInt()
            val expectedFrameLength = expectedDataLength + 2 // 2 bytes for length prepended
            val lengthBytes = ByteBuffer.wrap(ByteArray(2))
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(expectedDataLength.toShort())
                .array()
            val msgBytes = msg.bytes
            assertContentEquals(lengthBytes, msgBytes.copyOfRange(0, 2))
            assertEquals(expectedFrameLength, msgBytes.size)
        }
    }

    @Test
    fun `inbound CspFrames with fewer than 20 bytes must be ignored`() {
        // Arrange
        val controller = mockController()
        val cspSessionState = mockk<CspSessionState>()
        every { (controller.cspSessionState) } returns cspSessionState
        every { cspSessionState.isLoginDone } returns true

        val layer = MultiplexLayer(controller)

        val source = InputPipe<InboundL1Message, ServerSocketCloseReason>()
        val handler = PipeHandlerHelper<InboundL2Message>()

        source.pipeThrough(layer.decoder).setHandler(handler)

        repeat(20) {
            source.send(CspData(ByteArray(it)))
            assertEquals(0, handler.messages.size)
        }
    }

    @Test
    fun `inbound CspFrames with more than 19 bytes must be processed`() {
        // Arrange
        val controller = mockController()
        val cspSessionState = mockk<CspSessionState>()
        every { controller.cspSessionState } returns cspSessionState
        // login done; map to frames
        every { cspSessionState.isLoginDone } returns true

        val layer = MultiplexLayer(controller)

        val source = InputPipe<InboundL1Message, ServerSocketCloseReason>()
        val handler = PipeHandlerHelper<InboundL2Message>()

        source.pipeThrough(layer.decoder).setHandler(handler)

        val sizes = intArrayOf(20, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192)

        sizes.forEach { size ->
            val expectedBytes = ByteArray(size) { size.toByte() }
            source.send(CspData(expectedBytes))
        }
        assertEquals(sizes.size, handler.messages.size)
        handler.messages.forEachIndexed { idx, frame ->
            val expectedSize = sizes[idx]

            assertTrue(frame is CspFrame)
            assertEquals(expectedSize, frame.box.size)
        }
    }

    @Test
    fun `inbound CspLoginMessages must correctly be mapped`() {
        // Arrange
        val controller = mockController()
        val cspSessionState = mockk<CspSessionState>()
        every { controller.cspSessionState } returns cspSessionState
        // Login is not done, map to login messages
        every { cspSessionState.isLoginDone } returns false

        val layer = MultiplexLayer(controller)

        val source = InputPipe<InboundL1Message, ServerSocketCloseReason>()
        val handler = PipeHandlerHelper<InboundL2Message>()

        source.pipeThrough(layer.decoder).setHandler(handler)

        val sizes =
            intArrayOf(ProtocolDefines.SERVER_HELLO_LEN, ProtocolDefines.SERVER_LOGIN_ACK_LEN)

        sizes.forEach { size ->
            val expectedBytes = ByteArray(size) { size.toByte() }
            source.send(CspData(expectedBytes))
        }
        assertEquals(sizes.size, handler.messages.size)
        handler.messages.forEachIndexed { idx, loginMessage ->
            val expectedSize = sizes[idx]

            assertTrue(loginMessage is CspLoginMessage)
            assertEquals(expectedSize, loginMessage.bytes.size)
        }
    }

    private fun mockController(): ServerConnectionController {
        val dispatcher = mockk<ServerConnectionDispatcher>(relaxed = true)
        val controller = mockk<ServerConnectionController>()
        every { controller.dispatcher } returns dispatcher
        return controller
    }
}

private class PipeHandlerHelper<T> : PipeHandler<T> {
    private val _messages = mutableListOf<T>()

    val messages: List<T> = _messages

    override fun handle(msg: T) {
        _messages.add(msg)
    }
}
