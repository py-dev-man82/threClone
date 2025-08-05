/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2017-2025 Threema GmbH
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

package ch.threema.app.rating;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import org.slf4j.Logger;

import java.util.Optional;

import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.dialogs.ThreemaDialogFragment;
import ch.threema.app.managers.ServiceManager;
import ch.threema.app.preference.service.PreferenceService;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.DialogUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.base.ThreemaException;
import ch.threema.base.utils.LoggingUtil;
import ch.threema.domain.protocol.ServerAddressProvider;
import okhttp3.OkHttpClient;

import static ch.threema.app.utils.ActiveScreenLoggerKt.logScreenVisibility;

public class RateDialog extends ThreemaDialogFragment {
    private static final Logger logger = LoggingUtil.getThreemaLogger("RateDialog");

    private static final String BUNDLE_RATE_STAR = "rs";
    private RateDialogClickListener callback;
    private Context context;
    private AlertDialog alertDialog;
    private int rating;
    private TextInputEditText editText = null;
    private String tag = null;
    private PreferenceService preferenceService;
    private ServerAddressProvider serverAddressProvider;
    private OkHttpClient okHttpClient;

    private final Integer[] starMap = {
        R.id.star_one,
        R.id.star_two,
        R.id.star_three,
        R.id.star_four,
        R.id.star_five
    };

    public static RateDialog newInstance(String title) {
        RateDialog dialog = new RateDialog();
        Bundle args = new Bundle();
        args.putString("title", title);

        dialog.setArguments(args);
        return dialog;
    }

    public interface RateDialogClickListener {
        void onYes(String tag, int rating, String text);

        void onCancel(String tag);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logScreenVisibility(this, logger);

        ServiceManager serviceManager = ThreemaApplication.getServiceManager();
        if (serviceManager == null) {
            dismiss();
            return;
        }

        preferenceService = serviceManager.getPreferenceService();
        serverAddressProvider = serviceManager.getServerAddressProviderService().getServerAddressProvider();
        okHttpClient = serviceManager.getOkHttpClient();

        if (callback == null) {
            try {
                callback = (RateDialogClickListener) getTargetFragment();
            } catch (ClassCastException e) {
                //
            }

            // called from an activity rather than a fragment
            if (callback == null) {
                if (context instanceof RateDialogClickListener) {
                    callback = (RateDialogClickListener) context;
                }
            }
        }
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        this.context = context;
    }

    @Override
    @NonNull
    public AppCompatDialog onCreateDialog(Bundle savedInstanceState) {
        String title = requireArguments().getString("title");
        String positive = getString(R.string.rate_positive);
        String negative = getString(R.string.cancel);

        tag = this.getTag();

        final View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_rate, null);
        editText = dialogView.findViewById(R.id.feedback_edittext);
        final LinearLayout feedbackLayout = dialogView.findViewById(R.id.feedback_layout);

        if (savedInstanceState != null) {
            rating = savedInstanceState.getInt(BUNDLE_RATE_STAR, 0);
            onStarClick(rating, feedbackLayout, dialogView);
        }

        for (int i = 0; i < starMap.length; i++) {
            ImageView starView = dialogView.findViewById(starMap[i]);
            starView.setTag(i + 1);
            starView.setOnClickListener(v -> onStarClick((int) v.getTag(), feedbackLayout, dialogView));
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context, getTheme());
        builder.setView(dialogView);

        if (!TestUtil.isEmptyOrNull(title)) {
            builder.setTitle(title);
        }

        if (preferenceService != null) {
            String review = preferenceService.getRatingReviewText();
            if (!TestUtil.isEmptyOrNull(review)) {
                editText.append(review);
            }
        }

        builder.setPositiveButton(positive, null);
        builder.setNegativeButton(negative, (dialog, whichButton) -> callback.onCancel(tag));

        alertDialog = builder.create();

        setCancelable(false);

        return alertDialog;
    }

    @SuppressLint("StaticFieldLeak")
    private void sendReview(final String tag, final int rating, final String text) {
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected void onPreExecute() {
                alertDialog.findViewById(R.id.text_input_layout).setVisibility(View.INVISIBLE);
                alertDialog.findViewById(R.id.progress_bar).setVisibility(View.VISIBLE);
                alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setVisibility(View.INVISIBLE);
                alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setVisibility(View.INVISIBLE);
            }

            @Override
            protected Boolean doInBackground(Void... params) {
                if (!TestUtil.isEmptyOrNull(text)) {
                    preferenceService.setRatingReviewText(text);
                }

                // Create the rating service to send the rating
                RatingService ratingService = new RatingService(
                    new RatingReferenceProvider(preferenceService),
                    okHttpClient,
                    serverAddressProvider
                );

                // simulate some activity to show progress bar
                SystemClock.sleep(1500);
                try {
                    ratingService.sendRating(
                        rating,
                        text,
                        ConfigUtils.getRatingAppVersion()
                    );
                    return true;
                } catch (ThreemaException e) {
                    logger.error("Failed to send rating", e);
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean success) {
                if (isAdded()) {
                    if (success) {
                        callback.onYes(tag, rating, text);
                        dismiss();
                    } else {
                        Toast.makeText(ThreemaApplication.getAppContext(), getString(R.string.rate_error), Toast.LENGTH_LONG).show();
                        alertDialog.findViewById(R.id.text_input_layout).setVisibility(View.VISIBLE);
                        alertDialog.findViewById(R.id.progress_bar).setVisibility(View.GONE);
                        alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setVisibility(View.VISIBLE);
                        alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setVisibility(View.VISIBLE);
                    }
                }
            }
        }.execute();
    }

    private void onStarClick(int currentRating, View feedbackLayout, View dialogView) {
        rating = currentRating;

        updateStarDisplay(dialogView);

        if (rating > 0) {
            if (!feedbackLayout.isShown()) {
                toggleLayout(feedbackLayout, true);
            }
        } else {
            if (feedbackLayout.isShown()) {
                toggleLayout(feedbackLayout, false);
            }
        }
    }

    private void updateStarDisplay(View dialogView) {
        for (int i = 0; i < starMap.length; i++) {
            ImageView v = dialogView.findViewById(starMap[i]);
            v.setImageResource(i < rating ? R.drawable.ic_star_golden_24dp : R.drawable.ic_star_outline_24dp);
        }
        if (alertDialog != null) {
            alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
        }
    }

    private void toggleLayout(View v, boolean show) {
        InputMethodManager imm = (InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);

        if (!show) {
            slide_up(v);
            v.setVisibility(View.GONE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            }
        } else {
            v.setVisibility(View.VISIBLE);
            slide_down(v);
            v.requestFocus();
            if (imm != null) {
                imm.showSoftInput(v, 0);
            }
        }
    }

    public void slide_down(View v) {
        Animation a = AnimationUtils.loadAnimation(context, R.anim.slide_down);
        if (a != null) {
            a.reset();
            if (v != null) {
                v.clearAnimation();
                v.startAnimation(a);
            }
        }
    }

    public void slide_up(View v) {
        Animation a = AnimationUtils.loadAnimation(context, R.anim.slide_up);
        if (a != null) {
            a.reset();
            if (v != null) {
                v.clearAnimation();
                v.startAnimation(a);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        if (alertDialog != null) {
            Button positiveButton = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            Button negativeButton = alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE);

            positiveButton.setEnabled(rating > 0);
            ColorStateList colorStateList = DialogUtil.getButtonColorStateList(context);
            positiveButton.setTextColor(colorStateList);
            negativeButton.setTextColor(colorStateList);

            positiveButton.setOnClickListener(v -> {
                String text = Optional
                    .ofNullable(editText.getText())
                    .map(Object::toString)
                    .orElse("");
                sendReview(tag, rating, text);
            });
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putInt(BUNDLE_RATE_STAR, rating);
    }
}
