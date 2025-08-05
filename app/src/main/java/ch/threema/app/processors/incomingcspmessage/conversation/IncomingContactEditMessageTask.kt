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

package ch.threema.app.processors.incomingcspmessage.conversation

import ch.threema.app.managers.ServiceManager
import ch.threema.app.messagereceiver.ContactMessageReceiver
import ch.threema.app.processors.incomingcspmessage.IncomingCspMessageSubTask
import ch.threema.app.processors.incomingcspmessage.ReceiveStepsResult
import ch.threema.app.tasks.runCommonEditMessageReceiveSteps
import ch.threema.base.utils.LoggingUtil
import ch.threema.domain.protocol.csp.messages.EditMessage
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.TriggerSource
import ch.threema.storage.models.AbstractMessageModel

private val logger = LoggingUtil.getThreemaLogger("IncomingContactEditMessageTask")

class IncomingContactEditMessageTask(
    editMessage: EditMessage,
    triggerSource: TriggerSource,
    serviceManager: ServiceManager,
) : IncomingCspMessageSubTask<EditMessage>(editMessage, triggerSource, serviceManager) {
    private val messageService by lazy { serviceManager.messageService }
    private val contactService by lazy { serviceManager.contactService }

    override suspend fun executeMessageStepsFromRemote(handle: ActiveTaskCodec): ReceiveStepsResult {
        return applyEdit()
    }

    override suspend fun executeMessageStepsFromSync(): ReceiveStepsResult {
        return applyEdit()
    }

    private fun applyEdit(): ReceiveStepsResult {
        logger.debug("IncomingContactEditMessageTask id: {}", message.data.messageId)

        val contactModel = contactService.getByIdentity(message.fromIdentity)
        if (contactModel == null) {
            logger.warn("Incoming Edit Message: No contact found for ${message.fromIdentity}")
            return ReceiveStepsResult.DISCARD
        }

        val fromReceiver: ContactMessageReceiver = contactService.createReceiver(contactModel)
        val editedMessage: AbstractMessageModel =
            runCommonEditMessageReceiveSteps(message, fromReceiver, messageService)
                ?: return ReceiveStepsResult.DISCARD

        messageService.saveEditedMessageText(editedMessage, message.data.text, message.date)

        return ReceiveStepsResult.SUCCESS
    }
}
