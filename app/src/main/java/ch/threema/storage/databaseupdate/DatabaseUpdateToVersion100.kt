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

package ch.threema.storage.databaseupdate

import net.zetetic.database.sqlcipher.SQLiteDatabase

internal class DatabaseUpdateToVersion100(
    private val sqLiteDatabase: SQLiteDatabase,
) : DatabaseUpdate {
    override fun run() {
        sqLiteDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS `contact_edit_history_entries` (" +
                "`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`messageUid` VARCHAR NOT NULL, " +
                "`messageId` INTEGER NOT NULL, " +
                "`text` VARCHAR NOT NULL, " +
                "`editedAt` DATETIME NOT NULL, " +
                "CONSTRAINT fk_contact_message_id FOREIGN KEY(messageId) " +
                "REFERENCES message (id) ON UPDATE CASCADE ON DELETE CASCADE " +
                ")",
        )

        sqLiteDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS `group_edit_history_entries` (" +
                "`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`messageUid` VARCHAR NOT NULL, " +
                "`messageId` INTEGER NOT NULL, " +
                "`text` VARCHAR NOT NULL, " +
                "`editedAt` DATETIME NOT NULL, " +
                "CONSTRAINT fk_group_message_id FOREIGN KEY(messageId) " +
                "REFERENCES m_group_message (id) ON UPDATE CASCADE ON DELETE CASCADE " +
                ")",
        )
    }

    override fun getDescription() = "create edit message history entries tables"

    override fun getVersion() = VERSION

    companion object {
        const val VERSION = 100
    }
}
