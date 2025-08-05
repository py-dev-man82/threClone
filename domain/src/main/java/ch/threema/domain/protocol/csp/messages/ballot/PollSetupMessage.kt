/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2013-2025 Threema GmbH
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

package ch.threema.domain.protocol.csp.messages.ballot

import ch.threema.base.utils.LoggingUtil
import ch.threema.domain.protocol.csp.ProtocolDefines
import ch.threema.domain.protocol.csp.messages.AbstractMessage
import ch.threema.domain.protocol.csp.messages.BadMessageException
import ch.threema.protobuf.csp.e2e.fs.Version
import ch.threema.protobuf.d2d.MdD2D
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

private val logger = LoggingUtil.getThreemaLogger("PollSetupMessage")

/**
 * A poll creation message.
 */
open class PollSetupMessage : AbstractMessage(), BallotSetupInterface {
    override var ballotId: BallotId? = null
    override var ballotCreatorIdentity: String? = null
    override var ballotData: BallotData? = null

    override fun flagSendPush(): Boolean = true

    override fun getMinimumRequiredForwardSecurityVersion(): Version = Version.V1_0

    override fun allowUserProfileDistribution(): Boolean = true

    override fun exemptFromBlocking(): Boolean = false

    override fun createImplicitlyDirectContact(): Boolean = true

    override fun protectAgainstReplay(): Boolean = true

    override fun reflectIncoming(): Boolean = true

    override fun reflectOutgoing(): Boolean = true

    override fun reflectSentUpdate(): Boolean = true

    override fun sendAutomaticDeliveryReceipt(): Boolean = true

    override fun bumpLastUpdate(): Boolean = true

    override fun getBody(): ByteArray? {
        if (ballotId == null || ballotData == null) {
            return null
        }
        try {
            val bos = ByteArrayOutputStream()
            bos.write(ballotId!!.ballotId)
            ballotData!!.write(bos)
            return bos.toByteArray()
        } catch (exception: Exception) {
            logger.error(exception.message)
            return null
        }
    }

    override fun getType(): Int = ProtocolDefines.MSGTYPE_BALLOT_CREATE

    override fun toString(): String =
        StringBuilder().apply {
            append(super.toString())
            append(": poll setup message")
            ballotData?.let { ballotDataNotNull ->
                append(", description: ")
                append(ballotDataNotNull.description)
            }
        }.toString()

    companion object {
        @JvmStatic
        fun fromReflected(
            message: MdD2D.IncomingMessage,
            ballotCreatorIdentity: String,
        ): PollSetupMessage = fromByteArray(
            data = message.body.toByteArray(),
            ballotCreatorIdentity = ballotCreatorIdentity,
        ).apply {
            initializeCommonProperties(message)
        }

        @JvmStatic
        fun fromReflected(
            message: MdD2D.OutgoingMessage,
            ballotCreatorIdentity: String,
        ): PollSetupMessage = fromByteArray(
            data = message.body.toByteArray(),
            ballotCreatorIdentity = ballotCreatorIdentity,
        ).apply {
            initializeCommonProperties(message)
        }

        @JvmStatic
        @Throws(BadMessageException::class)
        fun fromByteArray(data: ByteArray, ballotCreatorIdentity: String): PollSetupMessage =
            fromByteArray(
                data = data,
                offset = 0,
                length = data.size,
                ballotCreatorIdentity = ballotCreatorIdentity,
            )

        /**
         * Get the poll-setup message from the given array.
         *
         * @param data   the data that represents the message
         * @param offset the offset where the data starts
         * @param length the length of the data (needed to ignore the padding)
         * @param ballotCreatorIdentity the identity of the sender
         * @return the poll-setup message
         * @throws BadMessageException if the length is invalid
         */
        @JvmStatic
        @Throws(BadMessageException::class)
        fun fromByteArray(
            data: ByteArray,
            offset: Int,
            length: Int,
            ballotCreatorIdentity: String,
        ): PollSetupMessage {
            if (length < 1) {
                throw BadMessageException("Bad length ($length) for poll setup message")
            } else if (offset < 0) {
                throw BadMessageException("Bad offset ($offset) for poll setup message")
            } else if (data.size < length + offset) {
                throw BadMessageException("Invalid byte array length (${data.size}) for offset $offset and length $length")
            }

            return PollSetupMessage().apply {
                this.ballotCreatorIdentity = ballotCreatorIdentity

                var positionIndex = offset
                ballotId = BallotId(data, positionIndex)

                positionIndex += ProtocolDefines.BALLOT_ID_LEN

                val jsonObjectString = String(
                    data,
                    positionIndex,
                    length + offset - positionIndex,
                    StandardCharsets.UTF_8,
                )
                ballotData = BallotData.parse(jsonObjectString)
            }
        }
    }
}
