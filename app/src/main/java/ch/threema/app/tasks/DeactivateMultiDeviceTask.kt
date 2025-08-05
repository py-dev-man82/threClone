/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2025 Threema GmbH
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
import ch.threema.app.multidevice.unlinking.DropDevicesIntent
import ch.threema.app.multidevice.unlinking.runDropDevicesSteps
import ch.threema.base.utils.LoggingUtil
import ch.threema.domain.taskmanager.ActiveTask
import ch.threema.domain.taskmanager.ActiveTaskCodec
import kotlinx.serialization.Serializable

private val logger = LoggingUtil.getThreemaLogger("DeactivateMultiDeviceTask")

/**
 * This task must be run when multi device should be deactivated. It will drop all other devices, followed by this device. Afterwards, the feature
 * mask is updated to support fs and this is communicated with the contacts.
 */
class DeactivateMultiDeviceTask(private val serviceManager: ServiceManager) : ActiveTask<Unit>, PersistableTask {
    override val type = "DeactivateMultiDeviceTask"

    override suspend fun invoke(handle: ActiveTaskCodec) {
        if (!serviceManager.multiDeviceManager.isMultiDeviceActive) {
            logger.error("Multi device is already deactivated")
            return
        }

        runDropDevicesSteps(
            intent = DropDevicesIntent.Deactivate,
            serviceManager = serviceManager,
            handle = handle,
        )
    }

    @Serializable
    data object DeactivateMultiDeviceTaskData : SerializableTaskData {
        override fun createTask(serviceManager: ServiceManager) = DeactivateMultiDeviceTask(serviceManager)
    }

    override fun serialize() = DeactivateMultiDeviceTaskData
}
