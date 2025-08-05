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

package ch.threema.app.tasks

import ch.threema.app.managers.ServiceManager
import ch.threema.app.messagereceiver.MessageReceiver
import ch.threema.app.messagereceiver.MessageReceiver.MessageReceiverType
import ch.threema.base.utils.LoggingUtil
import ch.threema.domain.protocol.csp.ProtocolDefines
import ch.threema.domain.protocol.csp.messages.file.FileData
import ch.threema.domain.protocol.csp.messages.file.FileMessage
import ch.threema.domain.protocol.csp.messages.file.GroupFileMessage
import ch.threema.domain.taskmanager.ActiveTaskCodec
import ch.threema.domain.taskmanager.Task
import ch.threema.domain.taskmanager.TaskCodec
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.data.media.FileDataModel
import kotlinx.serialization.Serializable

private val logger = LoggingUtil.getThreemaLogger("OutgoingFileMessageTask")

class OutgoingFileMessageTask(
    private val messageModelId: Int,
    @MessageReceiverType
    private val receiverType: Int,
    private val recipientIdentities: Set<String>,
    private val thumbnailBlobId: ByteArray?,
    serviceManager: ServiceManager,
) : OutgoingCspMessageTask(serviceManager) {
    private val myIdentity by lazy { serviceManager.userService.identity }

    private val isMultiDeviceActive by lazy { serviceManager.multiDeviceManager.isMultiDeviceActive }

    override val type: String = "OutgoingFileMessageTask"

    override suspend fun runSendingSteps(handle: ActiveTaskCodec) {
        when (receiverType) {
            MessageReceiver.Type_CONTACT -> sendContactMessage(handle)
            MessageReceiver.Type_GROUP -> sendGroupMessage(handle)
            else -> throw IllegalStateException("Invalid message receiver type $receiverType")
        }
    }

    override fun onSendingStepsFailed(e: Exception) {
        getMessageModel(receiverType, messageModelId)?.saveWithStateFailed()
    }

    private suspend fun sendContactMessage(handle: ActiveTaskCodec) {
        val messageModel = getContactMessageModel(messageModelId) ?: return

        val fileDataModel = messageModel.fileData

        val apiMessageId = ensureMessageId(messageModel)

        // Create the message
        val message = FileMessage().apply {
            fileData = fileDataModel.toFileData(thumbnailBlobId, messageModel)
            toIdentity = messageModel.identity
            messageId = apiMessageId
        }

        sendContactMessage(
            message,
            messageModel,
            messageModel.identity!!,
            apiMessageId,
            messageModel.createdAt!!,
            handle,
        )
    }

    private suspend fun sendGroupMessage(handle: ActiveTaskCodec) {
        val messageModel = getGroupMessageModel(messageModelId) ?: return

        val group = groupService.getById(messageModel.groupId)
            ?: throw IllegalStateException("Could not get group for message model ${messageModel.apiMessageId}")

        val fileDataModel = messageModel.fileData

        sendGroupMessage(
            group,
            recipientIdentities,
            messageModel,
            messageModel.createdAt!!,
            ensureMessageId(messageModel),
            {
                GroupFileMessage().apply {
                    fileData = fileDataModel.toFileData(thumbnailBlobId, messageModel)
                }
            },
            handle,
        )
    }

    private fun FileDataModel.toFileData(
        thumbnailBlobId: ByteArray?,
        messageModel: AbstractMessageModel,
    ): FileData {
        // In case there are recipients or multi device is active, we need a blob id and an
        // encryption key. Otherwise the message will be invalid and cannot be sent. In case there
        // is no recipient and multi device is not active, the message is sent in a notes group
        // where we do not upload the blob.
        if (recipientIdentities.minus(myIdentity).isNotEmpty() || isMultiDeviceActive) {
            // Validate that the blob id has the correct length
            if (blobId == null || blobId.size != ProtocolDefines.BLOB_ID_LEN) {
                logger.error("Invalid blob id of length {}", blobId?.size)
                throw IllegalStateException("Invalid blob id")
            }

            // Validate that the encryption key has the correct length
            if (encryptionKey == null || encryptionKey.size != ProtocolDefines.BLOB_KEY_LEN) {
                logger.error("Invalid encryption key of length {}", encryptionKey?.size)
                throw IllegalStateException("Invalid blob encryption key")
            }
        }

        return FileData().also {
            it.fileBlobId = blobId
            it.thumbnailBlobId = thumbnailBlobId
            it.encryptionKey = encryptionKey
            it.mimeType = mimeType
            it.thumbnailMimeType = thumbnailMimeType
            it.fileSize = fileSize
            it.fileName = fileName
            it.renderingType = renderingType
            it.caption = caption
            it.correlationId = messageModel.correlationId
            it.metaData = metaData
        }
    }

    override fun serialize(): SerializableTaskData = OutgoingFileMessageData(
        messageModelId,
        receiverType,
        recipientIdentities,
        thumbnailBlobId,
    )

    @Serializable
    class OutgoingFileMessageData(
        private val messageModelId: Int,
        @MessageReceiverType
        private val receiverType: Int,
        private val recipientIdentities: Set<String>,
        private val thumbnailBlobId: ByteArray?,
    ) : SerializableTaskData {
        override fun createTask(serviceManager: ServiceManager): Task<*, TaskCodec> =
            OutgoingFileMessageTask(
                messageModelId,
                receiverType,
                recipientIdentities,
                thumbnailBlobId,
                serviceManager,
            )
    }
}
