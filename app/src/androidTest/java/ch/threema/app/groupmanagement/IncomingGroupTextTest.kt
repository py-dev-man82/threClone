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

package ch.threema.app.groupmanagement

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import ch.threema.app.DangerousTest
import ch.threema.domain.protocol.csp.messages.GroupTextMessage
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.runner.RunWith

/**
 * Tests that the common group receive steps are executed for a group text message.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@DangerousTest
class IncomingGroupTextTest : GroupControlTest<GroupTextMessage>() {
    @Test
    fun testForwardSecureTextMessages() = runBlocking {
        val firstMessage = GroupTextMessage()
        firstMessage.fromIdentity = contactA.identity
        firstMessage.toIdentity = myContact.identity
        firstMessage.text = "First"
        firstMessage.groupCreator = groupA.groupCreator.identity
        firstMessage.apiGroupId = groupA.apiGroupId

        // We enforce forward secure messages in the TestTaskCodec and forward security status
        // listener. Therefore it is sufficient to test that processing a message succeeds.
        processMessage(firstMessage, contactA.identityStore)

        Assert.assertTrue(sentMessagesInsideTask.isEmpty())
        Assert.assertTrue(sentMessagesNewTask.isEmpty())
    }

    override fun createMessageForGroup(): GroupTextMessage {
        return GroupTextMessage().apply { text = "Group text message" }
    }
}
