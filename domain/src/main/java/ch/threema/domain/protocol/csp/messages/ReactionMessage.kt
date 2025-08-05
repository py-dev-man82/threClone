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

package ch.threema.domain.protocol.csp.messages

import ch.threema.domain.protocol.csp.ProtocolDefines
import ch.threema.domain.protocol.csp.messages.protobuf.AbstractProtobufMessage
import ch.threema.protobuf.csp.e2e.fs.Version
import ch.threema.protobuf.d2d.MdD2D

class ReactionMessage(payloadData: ReactionMessageData) :
    AbstractProtobufMessage<ReactionMessageData>(
        ProtocolDefines.MSGTYPE_REACTION,
        payloadData,
    ) {
    override fun getMinimumRequiredForwardSecurityVersion() = Version.V1_1

    override fun allowUserProfileDistribution() = true

    override fun exemptFromBlocking() = false

    override fun createImplicitlyDirectContact() = false

    override fun protectAgainstReplay() = true

    override fun reflectIncoming() = true

    override fun reflectOutgoing() = true

    override fun sendAutomaticDeliveryReceipt() = false

    override fun bumpLastUpdate() = false

    override fun flagSendPush() = true

    override fun reflectSentUpdate() = false

    companion object {
        /**
         *  When the message bytes come from sync (reflected), they do not contain the one extra byte at the beginning.
         *  So we set the offset in [fromByteArray] to zero.
         *
         *  In addition the common message model properties ([fromIdentity], [messageId] and [date]) get set.
         *
         *  @param message the MdD2D message representing the reaction message
         *  @return Instance of [ReactionMessage]
         *  @see fromByteArray
         */
        @JvmStatic
        fun fromReflected(message: MdD2D.IncomingMessage): ReactionMessage {
            val reactionMessage = fromByteArray(message.body.toByteArray())
            reactionMessage.initializeCommonProperties(message)
            return reactionMessage
        }

        @JvmStatic
        fun fromReflected(message: MdD2D.OutgoingMessage): ReactionMessage {
            val reactionMessage = fromByteArray(message.body.toByteArray())
            reactionMessage.initializeCommonProperties(message)
            return reactionMessage
        }

        @JvmStatic
        @Throws(BadMessageException::class)
        fun fromByteArray(data: ByteArray): ReactionMessage = fromByteArray(data, 0, data.size)

        /**
         * Build an instance of [ReactionMessage] from the given [data] bytes. Note that
         * the common message model properties ([fromIdentity], [messageId] and [date]) will **not** be set.
         *
         * The [data] byte array consists of:
         *  - body protobuf bytes of [ReactionMessageData]
         *
         * @param data   the data that represents the reaction message
         * @param offset the offset where the actual data starts (inclusive)
         * @param length the length of the data (needed to ignore the padding)
         * @return Instance of [ReactionMessage]
         * @throws BadMessageException if the length or the offset is invalid
         * @see fromReflected
         */
        @JvmStatic
        @Throws(BadMessageException::class)
        fun fromByteArray(data: ByteArray, offset: Int, length: Int): ReactionMessage {
            if (length < 1) {
                throw BadMessageException("Bad length ($length) for reaction message")
            } else if (offset < 0) {
                throw BadMessageException("Bad offset ($offset) for reaction message")
            } else if (data.size < length + offset) {
                throw BadMessageException("Invalid byte array length (${data.size}) for offset $offset and length $length")
            }
            val reactionMessageData = ReactionMessageData.fromProtobuf(
                data.copyOfRange(offset, length + offset),
            )
            return ReactionMessage(
                payloadData = reactionMessageData,
            )
        }
    }
}
