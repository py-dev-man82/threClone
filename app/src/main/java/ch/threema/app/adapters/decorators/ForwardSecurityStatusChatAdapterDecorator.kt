/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2014-2025 Threema GmbH
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

package ch.threema.app.adapters.decorators

import android.content.Context
import ch.threema.app.R
import ch.threema.app.ui.listitemholder.ComposeMessageHolder
import ch.threema.app.utils.ConfigUtils
import ch.threema.app.utils.TestUtil
import ch.threema.storage.models.AbstractMessageModel
import ch.threema.storage.models.data.status.ForwardSecurityStatusDataModel.ForwardSecurityStatusType

class ForwardSecurityStatusChatAdapterDecorator(
    context: Context,
    messageModel: AbstractMessageModel?,
    helper: Helper?,
) : ChatAdapterDecorator(context, messageModel, helper) {
    override fun configureChatMessage(holder: ComposeMessageHolder, position: Int) {
        val statusDataModel = messageModel.forwardSecurityStatusData ?: return
        val body: String? = when (statusDataModel.status) {
            ForwardSecurityStatusType.STATIC_TEXT -> statusDataModel.staticText
            ForwardSecurityStatusType.MESSAGE_WITHOUT_FORWARD_SECURITY ->
                context.getString(R.string.message_without_forward_security)

            ForwardSecurityStatusType.FORWARD_SECURITY_RESET ->
                context.getString(R.string.forward_security_reset)

            ForwardSecurityStatusType.FORWARD_SECURITY_ESTABLISHED ->
                context.getString(R.string.forward_security_established)

            ForwardSecurityStatusType.FORWARD_SECURITY_ESTABLISHED_RX ->
                context.getString(R.string.forward_security_established_rx)

            ForwardSecurityStatusType.FORWARD_SECURITY_MESSAGE_OUT_OF_ORDER ->
                context.getString(R.string.forward_security_message_out_of_order)

            ForwardSecurityStatusType.FORWARD_SECURITY_MESSAGES_SKIPPED ->
                ConfigUtils.getSafeQuantityString(
                    context,
                    R.plurals.forward_security_messages_skipped,
                    statusDataModel.quantity,
                    statusDataModel.quantity,
                )

            ForwardSecurityStatusType.FORWARD_SECURITY_UNAVAILABLE_DOWNGRADE ->
                context.getString(R.string.forward_security_downgraded_status_message)

            ForwardSecurityStatusType.FORWARD_SECURITY_ILLEGAL_SESSION_STATE ->
                context.getString(R.string.forward_security_illegal_session_status_message)

            // TODO(ANDR-2519): Can this be removed when md supports fs? Maybe not, because theses statuses won't be rendered correctly if they have already been created
            ForwardSecurityStatusType.FORWARD_SECURITY_DISABLED ->
                context.getString(R.string.forward_security_disabled)

            else -> null
        }
        if (showHide(holder.bodyTextView, !TestUtil.isEmptyOrNull(body))) {
            holder.bodyTextView.text = body
        }
        setOnClickListener({
            // no action on onClick
        }, holder.messageBlockView)
    }
}
