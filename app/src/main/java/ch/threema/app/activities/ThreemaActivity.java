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

package ch.threema.app.activities;

import android.content.Intent;

import org.slf4j.Logger;

import ch.threema.app.ThreemaApplication;
import ch.threema.app.backuprestore.csv.BackupService;
import ch.threema.app.backuprestore.csv.RestoreService;
import ch.threema.app.services.ActivityService;
import ch.threema.app.services.UserService;
import ch.threema.app.utils.TestUtil;
import ch.threema.base.utils.LoggingUtil;

public abstract class ThreemaActivity extends ThreemaAppCompatActivity {
    private static final Logger logger = LoggingUtil.getThreemaLogger("ThreemaActivity");

    final static public int ACTIVITY_ID_WIZARDFIRST = 20001;
    final static public int ACTIVITY_ID_SETTINGS = 20002;
    final static public int ACTIVITY_ID_COMPOSE_MESSAGE = 20003;
    final static public int ACTIVITY_ID_ADD_CONTACT = 20004;
    final static public int ACTIVITY_ID_VERIFY_MOBILE = 20005;
    final static public int ACTIVITY_ID_CONTACT_DETAIL = 20007;
    final static public int ACTIVITY_ID_UNLOCK_MASTER_KEY = 20008;
    final static public int ACTIVITY_ID_PICK_CAMERA_EXTERNAL = 20011;
    final static public int ACTIVITY_ID_PICK_CAMERA_INTERNAL = 20012;
    final static public int ACTIVITY_ID_SET_PASSPHRASE = 20013;
    final static public int ACTIVITY_ID_CHANGE_PASSPHRASE = 20014;
    final static public int ACTIVITY_ID_RESET_PASSPHRASE = 20015;
    final static public int ACTIVITY_ID_RESTORE_KEY = 20016;
    final static public int ACTIVITY_ID_ENTER_SERIAL = 20017;
    final static public int ACTIVITY_ID_SHARE_CHAT = 20018;
    final static public int ACTIVITY_ID_SEND_MEDIA = 20019;
    final static public int ACTIVITY_ID_ATTACH_MEDIA = 20020;
    public static final int ACTIVITY_ID_CONFIRM_DEVICE_CREDENTIALS = 20021;
    final static public int ACTIVITY_ID_IMPORT_CONTACTS = 20022;
    final static public int ACTIVITY_ID_GROUP_ADD = 20028;
    final static public int ACTIVITY_ID_GROUP_DETAIL = 20029;
    final static public int ACTIVITY_ID_CHANGE_PASSPHRASE_UNLOCK = 20032;
    final static public int ACTIVITY_ID_MEDIA_VIEWER = 20035;
    public static final int ACTIVITY_ID_CREATE_BALLOT = 20037;
    final static public int ACTIVITY_ID_ID_SECTION = 20041;
    final static public int ACTIVITY_ID_BACKUP_PICKER = 20042;
    final static public int ACTIVITY_ID_COPY_BALLOT = 20043;
    public static final int ACTIVITY_ID_CHECK_LOCK = 20046;
    public static final int ACTIVITY_ID_PAINT = 20049;
    public static final int ACTIVITY_ID_PICK_MEDIA = 20050;
    public static final int ACTIVITY_ID_MANAGE_GROUP_LINKS = 20051;
    final static public int ACTIVITY_ID_WORK_INTRO = 20052;

    public static final int RESULT_RESTART = 40005;

    private boolean isResumed;
    private String myIdentity = null;

    @Override
    protected void onPause() {
        super.onPause();
        if (isPinLockable() && isResumed) {
            ActivityService.activityPaused(this);
            isResumed = false;
        }
    }

    @Override
    protected void onResume() {
        if (isPinLockable() && ActivityService.activityResumed(this)) {
            isResumed = true;   /* activityResumed can return false, in which case we should make sure not to call activityPaused the next time to maintain acquire/release balance */
        }

        if (BackupService.isRunning() || RestoreService.isRunning()) {
            Intent intent = new Intent(this, BackupRestoreProgressActivity.class);
            startActivity(intent);
            finish();
        }

        super.onResume();
    }

    @Override
    protected void onDestroy() {
        // onPause() and onStop() will not be invoked if finish() is called from within the onCreate() method.
        // This might occur, for example, if you detect an error during onCreate() and call finish() as a result.
        // In such a case, though, any cleanup you expected to be done in onPause() and onStop() will not be executed.
        if (isPinLockable() && isResumed) {
            ActivityService.activityDestroyed(this);
        }

        super.onDestroy();
    }

    @Override
    public void onUserInteraction() {
        if (isPinLockable()) {
            ActivityService.activityUserInteract(this);
        }
        super.onUserInteraction();
    }

    protected boolean isPinLockable() {
        return true;
    }

    final protected boolean requiredInstances() {
        if (!this.checkInstances()) {
            try {
                this.instantiate();
            } catch (Exception e) {
                logger.error("Instantiation failed", e);
                return false;
            }
        }
        return this.checkInstances();
    }

    protected boolean checkInstances() {
        return true;
    }

    protected void instantiate() {
    }

    protected String getMyIdentity() {
        if (this.myIdentity == null) {
            UserService userService = ThreemaApplication.requireServiceManager().getUserService();
            if (!TestUtil.isEmptyOrNull(userService.getIdentity())) {
                this.myIdentity = userService.getIdentity();
            }
        }
        return this.myIdentity;
    }
}
