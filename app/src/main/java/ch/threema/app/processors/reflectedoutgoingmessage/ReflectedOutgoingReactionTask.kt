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

package ch.threema.app.processors.reflectedoutgoingmessage

import ch.threema.app.managers.ServiceManager
import ch.threema.app.tasks.runCommonReactionMessageReceiveEmojiSequenceConversion
import ch.threema.app.tasks.runCommonReactionMessageReceiveSteps
import ch.threema.domain.protocol.csp.messages.ReactionMessage
import ch.threema.protobuf.Common
import ch.threema.protobuf.d2d.MdD2D.OutgoingMessage
import ch.threema.storage.models.AbstractMessageModel

internal class ReflectedOutgoingReactionTask(
    outgoingMessage: OutgoingMessage,
    serviceManager: ServiceManager,
) : ReflectedOutgoingContactMessageTask<ReactionMessage>(
    outgoingMessage = outgoingMessage,
    message = ReactionMessage.fromReflected(outgoingMessage),
    type = Common.CspE2eMessageType.REACTION,
    serviceManager = serviceManager,
) {
    private val messageService by lazy { serviceManager.messageService }

    override fun processOutgoingMessage() {
        val targetMessage: AbstractMessageModel = runCommonReactionMessageReceiveSteps(
            reactionMessage = message,
            receiver = messageReceiver,
            messageService = messageService,
        ) ?: return

        val emojiSequence: String = runCommonReactionMessageReceiveEmojiSequenceConversion(
            emojiSequenceBytes = message.data.emojiSequenceBytes,
        ) ?: return

        messageService.saveEmojiReactionMessage(
            /* targetMessage = */
            targetMessage,
            /* senderIdentity = */
            contactService.me.identity,
            /* actionCase = */
            message.data.actionCase,
            /* emojiSequence = */
            emojiSequence,
        )
    }
}
