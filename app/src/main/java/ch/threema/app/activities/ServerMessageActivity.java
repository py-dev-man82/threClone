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

import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.core.text.HtmlCompat;
import androidx.lifecycle.ViewModelProvider;

import org.slf4j.Logger;

import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.notifications.NotificationIDs;
import ch.threema.app.services.notification.NotificationService;
import ch.threema.app.ui.InsetSides;
import ch.threema.app.ui.ServerMessageViewModel;
import ch.threema.app.ui.SpacingValues;
import ch.threema.app.ui.ViewExtensionsKt;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.base.utils.LoggingUtil;

import static ch.threema.app.startup.AppStartupUtilKt.finishAndRestartLaterIfNotReady;
import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;

public class ServerMessageActivity extends ThreemaActivity {
    private final static Logger logger = LoggingUtil.getThreemaLogger("ServerMessageActivity");

    private NotificationService notificationService = null;

    private ServerMessageViewModel viewModel;

    private TextView serverMessageTextView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logScreenVisibility(this, logger);
        if (finishAndRestartLaterIfNotReady(this)) {
            return;
        }

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.warning);
        }

        setContentView(R.layout.activity_server_message);

        serverMessageTextView = findViewById(R.id.server_message_text);
        serverMessageTextView.setMovementMethod(LinkMovementMethod.getInstance());

        handleDeviceInsets();

        notificationService = ThreemaApplication.requireServiceManager().getNotificationService();

        viewModel = new ViewModelProvider(this).get(ServerMessageViewModel.class);

        findViewById(R.id.close_button).setOnClickListener(v -> viewModel.markServerMessageAsRead());

        viewModel.getServerMessage().observe(this, serverMessage -> {
            if (serverMessage == null) {
                // Cancel the server message notification as the "Another connection..." message
                // may be received several times. This would open another notification. Because the
                // message is the same, it is shown only once and therefore has been deleted at this
                // point.
                cancelServerMessageNotification();
                finish();
                return;
            }
            showServerMessage(serverMessage);
        });
    }

    private void handleDeviceInsets() {
        ViewExtensionsKt.applyDeviceInsetsAsPadding(
            findViewById(R.id.scroll_container),
            InsetSides.ltr(),
            SpacingValues.all(R.dimen.grid_unit_x2)
        );
        ViewExtensionsKt.applyDeviceInsetsAsMargin(
            findViewById(R.id.close_button),
            InsetSides.lbr(),
            SpacingValues.all(R.dimen.grid_unit_x2)
        );
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            viewModel.markServerMessageAsRead();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        viewModel.markServerMessageAsRead();
    }

    private void showServerMessage(@NonNull String message) {
        if (message.startsWith("Another connection")) {
            message = getString(R.string.another_connection_instructions, getString(R.string.app_name));
        }

        serverMessageTextView.setText(HtmlCompat.fromHtml(message, HtmlCompat.FROM_HTML_MODE_COMPACT));
    }

    private void cancelServerMessageNotification() {
        notificationService.cancel(NotificationIDs.SERVER_MESSAGE_NOTIFICATION_ID);
    }
}
