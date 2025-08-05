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

import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.text.Editable;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;

import org.slf4j.Logger;

import java.util.Arrays;

import ch.threema.app.AppConstants;
import ch.threema.app.GlobalAppState;
import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.dialogs.GenericProgressDialog;
import ch.threema.app.managers.ServiceManager;
import ch.threema.app.services.LifetimeService;
import ch.threema.app.services.PassphraseService;
import ch.threema.app.services.ThreemaPushService;
import ch.threema.app.ui.InsetSides;
import ch.threema.app.ui.SimpleTextWatcher;
import ch.threema.app.ui.SpacingValues;
import ch.threema.app.ui.ThreemaTextInputEditText;
import ch.threema.app.ui.ViewExtensionsKt;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.DialogUtil;
import ch.threema.app.utils.EditTextUtil;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.base.utils.LoggingUtil;
import ch.threema.localcrypto.MasterKey;

import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;

// Note: This should NOT extend ThreemaToolbarActivity
public class UnlockMasterKeyActivity extends ThreemaActivity {
    private static final Logger logger = LoggingUtil.getThreemaLogger("UnlockMasterKeyActivity");

    // Dialog tags
    private static final String DIALOG_TAG_UNLOCKING = "dtu";

    // Views
    private ThreemaTextInputEditText passphraseText;
    private TextInputLayout passphraseLayout;
    private MaterialButton unlockButton;

    private final MasterKey masterKey = ThreemaApplication.getMasterKey();

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logScreenVisibility(this, logger);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        setContentView(R.layout.activity_unlock_masterkey);

        ViewExtensionsKt.applyDeviceInsetsAsPadding(
            findViewById(R.id.top_view),
            InsetSides.all(),
            SpacingValues.horizontal(R.dimen.grid_unit_x2)
        );

        TextView infoText = findViewById(R.id.unlock_info);
        TypedArray array = getTheme().obtainStyledAttributes(new int[]{R.attr.colorOnSurface});
        infoText.getCompoundDrawables()[0].setColorFilter(array.getColor(0, -1), PorterDuff.Mode.SRC_IN);
        array.recycle();

        passphraseLayout = findViewById(R.id.passphrase_layout);
        passphraseText = findViewById(R.id.passphrase);
        passphraseText.addTextChangedListener(new PasswordWatcher());
        passphraseText.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                if (isValidEntry(passphraseText)) {
                    doUnlock();
                }
                return true;
            }
            return false;
        });
        passphraseText.setOnEditorActionListener((v, actionId, event) -> {
            boolean handled = false;
            if (actionId == EditorInfo.IME_ACTION_GO) {
                if (isValidEntry(passphraseText)) {
                    doUnlock();
                    handled = true;
                }
            }
            return handled;
        });

        unlockButton = findViewById(R.id.unlock_button);
        unlockButton.setOnClickListener(v -> doUnlock());
        unlockButton.setClickable(false);
        unlockButton.setEnabled(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Check if the key is unlocked!
        if (!this.justCheck() && !this.masterKey.isLocked()) {
            this.finish();
        }
    }

    private void doUnlock() {
        unlockButton.setEnabled(false);
        unlockButton.setClickable(false);

        // Hide keyboard to make error message visible on low resolution displays
        EditTextUtil.hideSoftKeyboard(this.passphraseText);

        this.unlock(this.passphraseText.getPassphrase());
    }

    public class PasswordWatcher extends SimpleTextWatcher {
        @Override
        public void afterTextChanged(Editable s) {
            if (unlockButton != null) {
                if (passphraseText != null && passphraseText.getText() != null && passphraseText.getText().length() > 0) {
                    passphraseLayout.setError(null);
                }
                unlockButton.setEnabled(isValidEntry(passphraseText));
                unlockButton.setClickable(isValidEntry(passphraseText));
            }
        }
    }

    private boolean isValidEntry(EditText passphraseText) {
        return passphraseText != null && passphraseText.getText() != null && passphraseText.getText().length() >= 8;
    }

    private void unlock(final char[] passphrase) {
        final boolean justCheck = this.justCheck();

        if (justCheck || this.masterKey.isLocked()) {
            // Only change on master key!
            GenericProgressDialog.newInstance(R.string.masterkey_unlocking, R.string.please_wait).show(getSupportFragmentManager(), DIALOG_TAG_UNLOCKING);

            RuntimeUtil.runOnWorkerThread(() -> {
                boolean isValid;
                if (justCheck) {
                    isValid = masterKey.checkPassphrase(passphrase);
                } else {
                    isValid = masterKey.unlock(passphrase);
                }

                // clear passphrase
                Arrays.fill(passphrase, ' ');

                if (!isValid) {
                    RuntimeUtil.runOnUiThread(() -> {
                        passphraseLayout.setError(getString(R.string.invalid_passphrase));
                        passphraseText.setText("");
                    });
                } else {
                    if (justCheck) {
                        RuntimeUtil.runOnUiThread(() -> {
                            UnlockMasterKeyActivity.this.setResult(RESULT_OK);
                            UnlockMasterKeyActivity.this.finish();
                        });
                    } else {
                        // Finish after unlock
                        RuntimeUtil.runOnUiThread(() -> {
                            ThreemaApplication.onMasterKeyUnlocked(masterKey);

                            // Cancel all notifications...if any
                            NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(UnlockMasterKeyActivity.this);
                            notificationManagerCompat.cancelAll();

                            // Show persistent notification
                            PassphraseService.start(UnlockMasterKeyActivity.this.getApplicationContext());

                            // ServiceManager (and thus LifetimeService) are now available
                            // Trigger a connection
                            final ServiceManager serviceManager = ThreemaApplication.getServiceManager();
                            RuntimeUtil.runOnWorkerThread(() -> {
                                if (serviceManager != null) {
                                    final LifetimeService lifetimeService = serviceManager.getLifetimeService();
                                    if (lifetimeService != null) {
                                        if (GlobalAppState.isAppResumed()) {
                                            lifetimeService.acquireConnection(AppConstants.ACTIVITY_CONNECTION_TAG);
                                        } else {
                                            lifetimeService.ensureConnection();
                                        }
                                    }
                                }
                            });

                            // Start ThreemaPush service (which could not be started without an unlocked passphrase)
                            ThreemaPushService.tryStart(logger, getApplicationContext());

                            UnlockMasterKeyActivity.this.finish();
                        });
                    }
                }
                RuntimeUtil.runOnUiThread(() -> DialogUtil.dismissDialog(getSupportFragmentManager(), DIALOG_TAG_UNLOCKING, true));
            });
        } else {
            this.finish();
        }
    }

    private boolean justCheck() {
        return getIntent().getBooleanExtra(AppConstants.INTENT_DATA_PASSPHRASE_CHECK, false);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        // We override this method to avoid restarting the entire
        // activity when the keyboard is opened or orientation changes
        super.onConfigurationChanged(newConfig);
    }
}
