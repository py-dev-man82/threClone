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

package ch.threema.domain.protocol.csp;

import com.neilalexander.jnacl.NaCl;

public class ProtocolDefines {

    /* Timers and timeouts (in seconds) */
    public static final int CONNECT_TIMEOUT = 15;
    public static final int CONNECT_TIMEOUT_IPV6 = 3;

    public static final int READ_TIMEOUT = 20;
    public static final int WRITE_TIMEOUT = 20;
    public static final int BLOB_CONNECT_TIMEOUT = 30;
    public static final int BLOB_LOAD_TIMEOUT = 100;
    public static final int API_REQUEST_TIMEOUT = 20;

    public static final int RECONNECT_BASE_INTERVAL = 2;
    public static final int RECONNECT_MAX_INTERVAL = 10;

    // Echo request timeouts (in seconds)
    public static final short ECHO_RESPONSE_TIMEOUT = 10;
    public static final short ECHO_REQUEST_INTERVAL_CSP = 60;
    public static final short CONNECTION_IDLE_TIMEOUT_CSP = 120;
    public static final short ECHO_REQUEST_INTERVAL_MD = 15;
    public static final short CONNECTION_IDLE_TIMEOUT_MD = 30;

    /* object lengths */
    public static final int COOKIE_LEN = 16;
    public static final int PUSH_FROM_LEN = 32;
    public static final int IDENTITY_LEN = 8;
    public static final int MESSAGE_ID_LEN = 8;
    public static final int BLOB_ID_LEN = 16;
    public static final int BLOB_KEY_LEN = 32;
    public static final int GROUP_ID_LEN = 8;
    public static final int GROUP_INVITE_TOKEN_LEN = 16;
    public static final int BALLOT_ID_LEN = 8;
    public static final int GROUP_JOIN_MESSAGE_LEN = 100;

    public static final int BALLOT_STATE_LEN = 1;
    public static final int BALLOT_ASSESSMENT_TYPE_LEN = 1;
    public static final int BALLOT_VISIBILITY_LEN = 1;

    /* server handshake */
    public static final int SERVER_HELLO_BOXLEN = NaCl.PUBLICKEYBYTES + COOKIE_LEN + NaCl.BOXOVERHEAD;
    public static final int SERVER_HELLO_LEN = COOKIE_LEN + SERVER_HELLO_BOXLEN;
    public static final int SERVER_LOGIN_ACK_LEN = 32;
    public static final int EXTENSION_INDICATOR_LEN = 32;
    public static final int VOUCH_LEN = 32;
    public static final int RESERVED1_LEN = 24;
    public static final int RESERVED2_LEN = 16;
    public static final int LOGIN_LEN = IDENTITY_LEN + EXTENSION_INDICATOR_LEN + COOKIE_LEN + RESERVED1_LEN + VOUCH_LEN + RESERVED2_LEN;

    /* max message size */
    public static final int MAX_PKT_LEN = 8192;
    public static final int OVERHEAD_NACL_BOX = 16; // Excluding nonce
    public static final int OVERHEAD_PKT_HDR = 4;
    public static final int OVERHEAD_MSG_HDR = 88;
    public static final int OVERHEAD_BOX_HDR = 1;
    public static final int OVERHEAD_MAXPADDING = 255;
    public static final int MAX_MESSAGE_LEN = MAX_PKT_LEN
        - OVERHEAD_NACL_BOX * 2 // Both app-to-server and end-to-end
        - OVERHEAD_PKT_HDR
        - OVERHEAD_MSG_HDR
        - OVERHEAD_BOX_HDR
        - OVERHEAD_MAXPADDING;
    public static final int MAX_TEXT_MESSAGE_LEN = 6000;
    public static final int MIN_MESSAGE_PADDED_LEN = 32;

    /* message type */
    public static final int MSGTYPE_TEXT = 0x01;
    public static final int MSGTYPE_IMAGE = 0x02;
    public static final int MSGTYPE_LOCATION = 0x10;
    public static final int MSGTYPE_VIDEO = 0x13;
    public static final int MSGTYPE_AUDIO = 0x14;
    public static final int MSGTYPE_BALLOT_CREATE = 0x15;
    public static final int MSGTYPE_BALLOT_VOTE = 0x16;
    public static final int MSGTYPE_FILE = 0x17;
    public static final int MSGTYPE_CONTACT_SET_PHOTO = 0x18;
    public static final int MSGTYPE_CONTACT_DELETE_PHOTO = 0x19;
    public static final int MSGTYPE_CONTACT_REQUEST_PHOTO = 0x1a;
    public static final int MSGTYPE_GROUP_TEXT = 0x41;
    public static final int MSGTYPE_GROUP_LOCATION = 0x42;
    public static final int MSGTYPE_GROUP_IMAGE = 0x43;
    public static final int MSGTYPE_GROUP_VIDEO = 0x44;
    public static final int MSGTYPE_GROUP_AUDIO = 0x45;
    public static final int MSGTYPE_GROUP_FILE = 0x46;
    public static final int MSGTYPE_GROUP_CREATE = 0x4a;
    public static final int MSGTYPE_GROUP_RENAME = 0x4b;
    public static final int MSGTYPE_GROUP_LEAVE = 0x4c;
    public static final int MSGTYPE_GROUP_JOIN_REQUEST = 0x4d;
    public static final int MSGTYPE_GROUP_JOIN_RESPONSE = 0x4e;
    public static final int MSGTYPE_GROUP_CALL_START = 0x4f;
    public static final int MSGTYPE_GROUP_SET_PHOTO = 0x50;
    public static final int MSGTYPE_GROUP_REQUEST_SYNC = 0x51;
    public static final int MSGTYPE_GROUP_BALLOT_CREATE = 0x52;
    public static final int MSGTYPE_GROUP_BALLOT_VOTE = 0x53;
    public static final int MSGTYPE_GROUP_DELETE_PHOTO = 0x54;
    public static final int MSGTYPE_VOIP_CALL_OFFER = 0x60;
    public static final int MSGTYPE_VOIP_CALL_ANSWER = 0x61;
    public static final int MSGTYPE_VOIP_ICE_CANDIDATES = 0x62;
    public static final int MSGTYPE_VOIP_CALL_HANGUP = 0x63;
    public static final int MSGTYPE_VOIP_CALL_RINGING = 0x64;
    public static final int MSGTYPE_DELIVERY_RECEIPT = 0x80;
    public static final int MSGTYPE_GROUP_DELIVERY_RECEIPT = 0x81;
    public static final int MSGTYPE_TYPING_INDICATOR = 0x90;
    public static final int MSGTYPE_FS_ENVELOPE = 0xa0;
    public static final int MSGTYPE_EMPTY = 0xfc;
    public static final int MSGTYPE_WEB_SESSION_RESUME = 0xfe;
    public static final int MSGTYPE_AUTH_TOKEN = 0xff;
    public static final int MSGTYPE_EDIT_MESSAGE = 0x91;
    public static final int MSGTYPE_DELETE_MESSAGE = 0x92;
    public static final int MSGTYPE_GROUP_EDIT_MESSAGE = 0x93;
    public static final int MSGTYPE_GROUP_DELETE_MESSAGE = 0x94;
    public static final int MSGTYPE_REACTION = 0x82;
    public static final int MSGTYPE_GROUP_REACTION = 0x83;

    /* message flags */
    // Note: Do not forget to update AbstractMessage#getMessageTypeDefaultFlags when adding a flag
    public static final int MESSAGE_FLAG_SEND_PUSH = 0x01;
    public static final int MESSAGE_FLAG_NO_SERVER_QUEUING = 0x02;
    public static final int MESSAGE_FLAG_NO_SERVER_ACK = 0x04;
    public static final int MESSAGE_FLAG_GROUP = 0x10;
    public static final int MESSAGE_FLAG_SHORT_LIVED = 0x20;
    public static final int MESSAGE_FLAG_NO_DELIVERY_RECEIPTS = 0x80;

    /* delivery receipt statuses */
    public static final int DELIVERYRECEIPT_MSGRECEIVED = 0x01;
    public static final int DELIVERYRECEIPT_MSGREAD = 0x02;
    public static final int DELIVERYRECEIPT_MSGUSERACK = 0x03;
    public static final int DELIVERYRECEIPT_MSGUSERDEC = 0x04;

    /* payload types */
    public static final int PLTYPE_ECHO_REQUEST = 0x00;
    public static final int PLTYPE_ECHO_REPLY = 0x80;
    public static final int PLTYPE_OUTGOING_MESSAGE = 0x01;
    public static final int PLTYPE_OUTGOING_MESSAGE_ACK = 0x81;
    public static final int PLTYPE_INCOMING_MESSAGE = 0x02;
    public static final int PLTYPE_INCOMING_MESSAGE_ACK = 0x82;
    public static final int PLTYPE_PUSH_NOTIFICATION_TOKEN = 0x20;
    public static final int PLTYPE_VOIP_PUSH_NOTIFICATION_TOKEN = 0x24;
    public static final int PLTYPE_DELETE_PUSH_NOTIFICATION_TOKEN = 0x25;
    public static final int PLTYPE_SET_CONNECTION_IDLE_TIMEOUT = 0x30;
    public static final int PLTYPE_QUEUE_SEND_COMPLETE = 0xd0;
    public static final int PLTYPE_DEVICE_COOKIE_CHANGE_INDICATION = 0xd2;
    public static final int PLTYPE_CLEAR_DEVICE_COOKIE_CHANGE_INDICATION = 0xd3;
    public static final int PLTYPE_ERROR = 0xe0;
    public static final int PLTYPE_ALERT = 0xe1;
    public static final int PLTYPE_UNBLOCK_INCOMING_MESSAGES = 0x03;

    /* push token types */
    public static final int PUSHTOKEN_TYPE_NONE = 0x00;
    public static final int PUSHTOKEN_TYPE_FCM = 0x11;
    public static final int PUSHTOKEN_TYPE_HMS = 0x13;

    /* nonces */
    public static final byte[] IMAGE_NONCE = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01};
    public static final byte[] VIDEO_NONCE = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01};
    public static final byte[] THUMBNAIL_NONCE = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02};
    public static final byte[] AUDIO_NONCE = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01};
    public static final byte[] GROUP_PHOTO_NONCE = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01};
    public static final byte[] CONTACT_PHOTO_NONCE = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01};
    public static final byte[] FILE_NONCE = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01};
    public static final byte[] FILE_THUMBNAIL_NONCE = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02};

    /* forward security */
    public static final int FS_SESSION_ID_LENGTH = 16;

    /* Group Calls */
    // TODO(ANDR-1974) Move gc-constants to other location
    public static final int GC_PROTOCOL_VERSION = 1;
    public static final int GC_CALL_ID_LENGTH = 32;
    public static final int GC_PCMK_LENGTH = 32;
    public static final int GC_PEEK_TIMEOUT_MILLIS = 5000;
    public static final int GC_JOIN_TIMEOUT_MILLIS = 20000;
    public static final long GC_GROUP_CALL_REFRESH_STEPS_TIMEOUT_SECONDS = 10;
    public static final long GC_GROUP_CALL_UPDATE_PERIOD_SECONDS = 10;
    public static final int GC_PEEK_FAILED_ABANDON_MIN_TRIES = 3;
    public static final long GC_PEEK_FAILED_ABANDON_MIN_CALL_AGE_MILLIS = 1000L * 60L * 60L * 10L; // 10 hours
    public static final String GC_ALLOWED_BASE_URL_PROTOCOL = "https";

    /* Auto Delete */
    public static final int AUTO_DELETE_KEEP_MESSAGES_DAYS_OFF_VALUE = 0;
    public static final int AUTO_DELETE_KEEP_MESSAGES_DAYS_MIN = 7;
    public static final int AUTO_DELETE_KEEP_MESSAGES_DAYS_MAX = 3650;

    /* Special Contacts */
    public static final String SPECIAL_CONTACT_PUSH = "*3MAPUSH";
}
