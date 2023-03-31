/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wifi.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.icu.text.MessageFormat;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiContext;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.BuildCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Main Activity of the WifiDialog application. All dialogs should be created and managed from here.
 */
public class WifiDialogActivity extends Activity  {
    private static final String TAG = "WifiDialog";
    private static final String KEY_DIALOG_INTENTS = "KEY_DIALOG_INTENTS";
    private static final String EXTRA_DIALOG_EXPIRATION_TIME_MS =
            "com.android.wifi.dialog.DIALOG_START_TIME_MS";
    private static final String EXTRA_DIALOG_P2P_PIN_INPUT =
            "com.android.wifi.dialog.DIALOG_P2P_PIN_INPUT";

    private @NonNull Handler mHandler = new Handler(Looper.getMainLooper());
    private @Nullable WifiContext mWifiContext;
    private @Nullable WifiManager mWifiManager;
    private boolean mIsVerboseLoggingEnabled;
    private int mGravity = Gravity.NO_GRAVITY;

    private @NonNull Set<Intent> mSavedStateIntents = new ArraySet<>();
    private @NonNull SparseArray<Intent> mLaunchIntentsPerId = new SparseArray<>();
    private @NonNull SparseArray<Dialog> mActiveDialogsPerId = new SparseArray<>();
    private @NonNull SparseArray<CountDownTimer> mActiveCountDownTimersPerId = new SparseArray<>();

    private WifiContext getWifiContext() {
        if (mWifiContext == null) {
            mWifiContext = new WifiContext(this);
        }
        return mWifiContext;
    }

    /**
     * Override the default Resources with the Resources from the active ServiceWifiResources APK.
     */
    @Override
    public Resources getResources() {
        return getWifiContext().getResources();
    }

    // TODO(b/215605937): Remove these getXxxId() methods with the actual resource ID references
    //                    once the build system is fixed to allow importing ServiceWifiResources.
    private int getStringId(@NonNull String name) {
        Resources res = getResources();
        return res.getIdentifier(
                name, "string", getWifiContext().getWifiOverlayApkPkgName());
    }

    private int getIntegerId(@NonNull String name) {
        Resources res = getResources();
        return res.getIdentifier(
                name, "integer", getWifiContext().getWifiOverlayApkPkgName());
    }

    private int getBooleanId(@NonNull String name) {
        Resources res = getResources();
        return res.getIdentifier(
                name, "bool", getWifiContext().getWifiOverlayApkPkgName());
    }

    private int getLayoutId(@NonNull String name) {
        Resources res = getResources();
        return res.getIdentifier(
                name, "layout", getWifiContext().getWifiOverlayApkPkgName());
    }

    private int getViewId(@NonNull String name) {
        Resources res = getResources();
        return res.getIdentifier(
                name, "id", getWifiContext().getWifiOverlayApkPkgName());
    }

    private int getStyleId(@NonNull String name) {
        Resources res = getResources();
        return res.getIdentifier(
                name, "style", getWifiContext().getWifiOverlayApkPkgName());
    }

    private WifiManager getWifiManager() {
        if (mWifiManager == null) {
            mWifiManager = getSystemService(WifiManager.class);
        }
        return mWifiManager;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
                return;
            }
            PowerManager powerManager = context.getSystemService(PowerManager.class);
            if (powerManager == null) {
                return;
            }
            if (!powerManager.isInteractive()) {
                // Ignore screen off case.
                return;
            }
            // Cancel all dialogs for ACTION_CLOSE_SYSTEM_DIALOGS (e.g. Home button pressed).
            for (int i = 0; i < mActiveDialogsPerId.size(); i++) {
                mActiveDialogsPerId.get(i).cancel();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerReceiver(mBroadcastReceiver, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                RECEIVER_NOT_EXPORTED);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        mIsVerboseLoggingEnabled = getWifiManager().isVerboseLoggingEnabled();
        if (mIsVerboseLoggingEnabled) {
            Log.v(TAG, "Creating WifiDialogActivity.");
        }
        mGravity = getResources().getInteger(getIntegerId("config_wifiDialogGravity"));
        List<Intent> receivedIntents = new ArrayList<>();
        if (savedInstanceState != null) {
            if (mIsVerboseLoggingEnabled) {
                Log.v(TAG, "Restoring WifiDialog saved state.");
            }
            List<Intent> savedStateIntents =
                    savedInstanceState.getParcelableArrayList(KEY_DIALOG_INTENTS);
            mSavedStateIntents.addAll(savedStateIntents);
            receivedIntents.addAll(savedStateIntents);
        } else {
            receivedIntents.add(getIntent());
        }
        for (Intent intent : receivedIntents) {
            int dialogId = intent.getIntExtra(WifiManager.EXTRA_DIALOG_ID,
                    WifiManager.INVALID_DIALOG_ID);
            if (dialogId == WifiManager.INVALID_DIALOG_ID) {
                if (mIsVerboseLoggingEnabled) {
                    Log.v(TAG, "Received Intent with invalid dialogId!");
                }
                continue;
            }
            mLaunchIntentsPerId.put(dialogId, intent);
        }
    }

    /**
     * Create and display a dialog for the currently held Intents.
     */
    @Override
    protected void onStart() {
        super.onStart();
        ArraySet<Integer> invalidDialogIds = new ArraySet<>();
        for (int i = 0; i < mLaunchIntentsPerId.size(); i++) {
            int dialogId = mLaunchIntentsPerId.keyAt(i);
            if (!createAndShowDialogForIntent(dialogId, mLaunchIntentsPerId.get(dialogId))) {
                invalidDialogIds.add(dialogId);
            }
        }
        invalidDialogIds.forEach(this::removeIntentAndPossiblyFinish);
    }

    /**
     * Create and display a dialog for a new Intent received by a pre-existing WifiDialogActivity.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) {
            return;
        }
        int dialogId = intent.getIntExtra(WifiManager.EXTRA_DIALOG_ID,
                WifiManager.INVALID_DIALOG_ID);
        if (dialogId == WifiManager.INVALID_DIALOG_ID) {
            if (mIsVerboseLoggingEnabled) {
                Log.v(TAG, "Received Intent with invalid dialogId!");
            }
            return;
        }
        String action = intent.getAction();
        if (WifiManager.ACTION_DISMISS_DIALOG.equals(action)) {
            removeIntentAndPossiblyFinish(dialogId);
            return;
        }
        mLaunchIntentsPerId.put(dialogId, intent);
        if (!createAndShowDialogForIntent(dialogId, intent)) {
            removeIntentAndPossiblyFinish(dialogId);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) {
            if (!BuildCompat.isAtLeastU()) {
                // Before U, we don't have INTERNAL_SYSTEM_WINDOW permission to always show at the
                // top, so close all dialogs when we're not visible anymore.
                for (int i = 0; i < mActiveDialogsPerId.size(); i++) {
                    mActiveDialogsPerId.get(i).cancel();
                }
            }
            return;
        }
        // If we're stopping due to a configuration change, dismiss all the dialogs without
        // removing it from mLaunchIntentsPerId to prevent window leaking. The dialogs will be
        // recreated from mLaunchIntentsPerId in onStart().
        for (int i = 0; i < mActiveDialogsPerId.size(); i++) {
            Dialog dialog = mActiveDialogsPerId.valueAt(i);
            // Set the dismiss listener to null to prevent removing the Intent from
            // mLaunchIntentsPerId.
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
        mActiveDialogsPerId.clear();
        for (int i = 0; i < mActiveCountDownTimersPerId.size(); i++) {
            mActiveCountDownTimersPerId.valueAt(i).cancel();
        }
        mActiveCountDownTimersPerId.clear();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mBroadcastReceiver);
        // We don't expect to be destroyed while dialogs are still up, but make sure to cancel them
        // just in case.
        for (int i = 0; i < mActiveDialogsPerId.size(); i++) {
            mActiveDialogsPerId.get(i).cancel();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        ArrayList<Intent> intentList = new ArrayList<>();
        for (int i = 0; i < mLaunchIntentsPerId.size(); i++) {
            intentList.add(mLaunchIntentsPerId.valueAt(i));
        }
        outState.putParcelableArrayList(KEY_DIALOG_INTENTS, intentList);
        super.onSaveInstanceState(outState);
    }

    /**
     * Remove the Intent and corresponding dialog of the given dialogId (cancelling it if it is
     * showing) and finish the Activity if there are no dialogs left to show.
     */
    private void removeIntentAndPossiblyFinish(int dialogId) {
        mLaunchIntentsPerId.remove(dialogId);
        Dialog dialog = mActiveDialogsPerId.get(dialogId);
        mActiveDialogsPerId.remove(dialogId);
        if (dialog != null && dialog.isShowing()) {
            dialog.cancel();
        }
        CountDownTimer timer = mActiveCountDownTimersPerId.get(dialogId);
        mActiveCountDownTimersPerId.remove(dialogId);
        if (timer != null) {
            timer.cancel();
        }
        if (mIsVerboseLoggingEnabled) {
            Log.v(TAG, "Dialog id " + dialogId + " removed.");
        }
        if (mLaunchIntentsPerId.size() == 0) {
            if (mIsVerboseLoggingEnabled) {
                Log.v(TAG, "No dialogs left to show, finishing.");
            }
            finishAndRemoveTask();
        }
    }

    /**
     * Creates and shows a dialog for the given dialogId and Intent.
     * Returns {@code true} if the dialog was successfully created, {@code false} otherwise.
     */
    private boolean createAndShowDialogForIntent(int dialogId, @NonNull Intent intent) {
        String action = intent.getAction();
        if (!WifiManager.ACTION_LAUNCH_DIALOG.equals(action)) {
            return false;
        }
        final AlertDialog dialog;
        int dialogType = intent.getIntExtra(
                WifiManager.EXTRA_DIALOG_TYPE, WifiManager.DIALOG_TYPE_UNKNOWN);
        switch (dialogType) {
            case WifiManager.DIALOG_TYPE_SIMPLE:
                dialog = createSimpleDialog(dialogId,
                        intent.getStringExtra(WifiManager.EXTRA_DIALOG_TITLE),
                        intent.getStringExtra(WifiManager.EXTRA_DIALOG_MESSAGE),
                        intent.getStringExtra(WifiManager.EXTRA_DIALOG_MESSAGE_URL),
                        intent.getIntExtra(WifiManager.EXTRA_DIALOG_MESSAGE_URL_START, 0),
                        intent.getIntExtra(WifiManager.EXTRA_DIALOG_MESSAGE_URL_END, 0),
                        intent.getStringExtra(WifiManager.EXTRA_DIALOG_POSITIVE_BUTTON_TEXT),
                        intent.getStringExtra(WifiManager.EXTRA_DIALOG_NEGATIVE_BUTTON_TEXT),
                        intent.getStringExtra(WifiManager.EXTRA_DIALOG_NEUTRAL_BUTTON_TEXT));
                break;
            case WifiManager.DIALOG_TYPE_P2P_INVITATION_SENT:
                dialog = createP2pInvitationSentDialog(
                        dialogId,
                        intent.getStringExtra(WifiManager.EXTRA_P2P_DEVICE_NAME),
                        intent.getStringExtra(WifiManager.EXTRA_P2P_DISPLAY_PIN));
                break;
            case WifiManager.DIALOG_TYPE_P2P_INVITATION_RECEIVED:
                dialog = createP2pInvitationReceivedDialog(
                        dialogId,
                        intent.getStringExtra(WifiManager.EXTRA_P2P_DEVICE_NAME),
                        intent.getBooleanExtra(WifiManager.EXTRA_P2P_PIN_REQUESTED, false),
                        intent.getStringExtra(WifiManager.EXTRA_P2P_DISPLAY_PIN));
                break;
            default:
                if (mIsVerboseLoggingEnabled) {
                    Log.v(TAG, "Could not create dialog with id= " + dialogId
                            + " for unknown type: " + dialogType);
                }
                return false;
        }
        dialog.setOnDismissListener((dialogDismiss) -> {
            if (mIsVerboseLoggingEnabled) {
                Log.v(TAG, "Dialog id=" + dialogId
                        + " dismissed.");
            }
            removeIntentAndPossiblyFinish(dialogId);
        });
        dialog.setCanceledOnTouchOutside(getResources().getBoolean(
                getBooleanId("config_wifiDialogCanceledOnTouchOutside")));
        if (mGravity != Gravity.NO_GRAVITY) {
            dialog.getWindow().setGravity(mGravity);
        }
        if (BuildCompat.isAtLeastU()) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        }
        mActiveDialogsPerId.put(dialogId, dialog);
        long timeoutMs = intent.getLongExtra(WifiManager.EXTRA_DIALOG_TIMEOUT_MS, 0);
        if (timeoutMs > 0) {
            // Use the original expiration time in case we've reloaded this dialog after a
            // configuration change.
            long expirationTimeMs = intent.getLongExtra(EXTRA_DIALOG_EXPIRATION_TIME_MS, 0);
            if (expirationTimeMs > 0) {
                timeoutMs = expirationTimeMs - SystemClock.uptimeMillis();
                if (timeoutMs < 0) {
                    timeoutMs = 0;
                }
            } else {
                intent.putExtra(
                        EXTRA_DIALOG_EXPIRATION_TIME_MS, SystemClock.uptimeMillis() + timeoutMs);
            }
            CountDownTimer countDownTimer = new CountDownTimer(timeoutMs, 100) {
                @Override
                public void onTick(long millisUntilFinished) {
                    if (dialogType == WifiManager.DIALOG_TYPE_P2P_INVITATION_RECEIVED) {
                        int secondsRemaining = (int) millisUntilFinished / 1000;
                        if (millisUntilFinished % 1000 != 0) {
                            // Round up to the nearest whole second.
                            secondsRemaining++;
                        }
                        dialog.setMessage(MessageFormat.format(
                                getString(getStringId("wifi_p2p_invitation_seconds_remaining")),
                                secondsRemaining));
                    }
                }

                @Override
                public void onFinish() {
                    removeIntentAndPossiblyFinish(dialogId);
                }
            }.start();
            mActiveCountDownTimersPerId.put(dialogId, countDownTimer);
        } else {
            if (dialogType == WifiManager.DIALOG_TYPE_P2P_INVITATION_RECEIVED) {
                // Set the message back to null if we aren't using a timeout.
                dialog.setMessage(null);
            }
        }
        dialog.show();
        if (mIsVerboseLoggingEnabled) {
            Log.v(TAG, "Showing dialog " + dialogId);
        }
        // Allow message URLs to be clickable.
        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) {
            messageView.setMovementMethod(LinkMovementMethod.getInstance());
        }
        // Play a notification sound/vibration if the dialog just came in (i.e. not read from the
        // saved instance state after a configuration change), and the overlays specify a
        // sound/vibration for the specific dialog type.
        if (!mSavedStateIntents.contains(intent)
                && dialogType == WifiManager.DIALOG_TYPE_P2P_INVITATION_RECEIVED
                && getResources().getBoolean(
                        getBooleanId("config_p2pInvitationReceivedDialogNotificationSound"))) {
            Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = RingtoneManager.getRingtone(this, notification);
            r.play();
            if (mIsVerboseLoggingEnabled) {
                Log.v(TAG, "Played notification sound for " + " dialogId=" + dialogId);
            }
            if (getSystemService(AudioManager.class).getRingerMode()
                    == AudioManager.RINGER_MODE_VIBRATE) {
                getSystemService(Vibrator.class).vibrate(1_000);
                if (mIsVerboseLoggingEnabled) {
                    Log.v(TAG, "Vibrated for " + " dialogId=" + dialogId);
                }
            }
        }
        return true;
    }

    /**
     * Returns a simple dialog for the given Intent.
     */
    private @NonNull AlertDialog createSimpleDialog(
            int dialogId,
            @Nullable String title,
            @Nullable String message,
            @Nullable String messageUrl,
            int messageUrlStart,
            int messageUrlEnd,
            @Nullable String positiveButtonText,
            @Nullable String negativeButtonText,
            @Nullable String neutralButtonText) {
        SpannableString spannableMessage = null;
        if (message != null) {
            spannableMessage = new SpannableString(message);
            if (messageUrl != null) {
                if (messageUrlStart < 0) {
                    Log.w(TAG, "Span start cannot be less than 0!");
                } else if (messageUrlEnd > message.length()) {
                    Log.w(TAG, "Span end index " + messageUrlEnd
                            + " cannot be greater than message length " + message.length() + "!");
                } else if (messageUrlStart > messageUrlEnd) {
                    Log.w(TAG, "Span start index cannot be greater than end index!");
                } else {
                    spannableMessage.setSpan(new URLSpan(messageUrl), messageUrlStart,
                            messageUrlEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        AlertDialog dialog = new AlertDialog.Builder(
                new ContextThemeWrapper(this, getStyleId("wifi_dialog")))
                .setTitle(title)
                .setMessage(spannableMessage)
                .setPositiveButton(positiveButtonText, (dialogPositive, which) -> {
                    if (mIsVerboseLoggingEnabled) {
                        Log.v(TAG, "Positive button pressed for simple dialog id="
                                + dialogId);
                    }
                    getWifiManager().replyToSimpleDialog(dialogId,
                            WifiManager.DIALOG_REPLY_POSITIVE);
                })
                .setNegativeButton(negativeButtonText, (dialogNegative, which) -> {
                    if (mIsVerboseLoggingEnabled) {
                        Log.v(TAG, "Negative button pressed for simple dialog id="
                                + dialogId);
                    }
                    getWifiManager().replyToSimpleDialog(dialogId,
                            WifiManager.DIALOG_REPLY_NEGATIVE);
                })
                .setNeutralButton(neutralButtonText, (dialogNeutral, which) -> {
                    if (mIsVerboseLoggingEnabled) {
                        Log.v(TAG, "Neutral button pressed for simple dialog id="
                                + dialogId);
                    }
                    getWifiManager().replyToSimpleDialog(dialogId,
                            WifiManager.DIALOG_REPLY_NEUTRAL);
                })
                .setOnCancelListener((dialogCancel) -> {
                    if (mIsVerboseLoggingEnabled) {
                        Log.v(TAG, "Simple dialog id=" + dialogId
                                + " cancelled.");
                    }
                    getWifiManager().replyToSimpleDialog(dialogId,
                            WifiManager.DIALOG_REPLY_CANCELLED);
                })
                .create();
        if (mIsVerboseLoggingEnabled) {
            Log.v(TAG, "Created a simple dialog."
                    + " id=" + dialogId
                    + " title=" + title
                    + " message=" + message
                    + " url=[" + messageUrl + "," + messageUrlStart + "," + messageUrlEnd + "]"
                    + " positiveButtonText=" + positiveButtonText
                    + " negativeButtonText=" + negativeButtonText
                    + " neutralButtonText=" + neutralButtonText);
        }
        return dialog;
    }

    /**
     * Returns a P2P Invitation Sent Dialog for the given Intent.
     */
    private @NonNull AlertDialog createP2pInvitationSentDialog(
            final int dialogId,
            @Nullable final String deviceName,
            @Nullable final String displayPin) {
        final View textEntryView = LayoutInflater.from(this)
                .inflate(getLayoutId("wifi_p2p_dialog"), null);
        ViewGroup group = textEntryView.findViewById(getViewId("info"));
        if (TextUtils.isEmpty(deviceName)) {
            Log.w(TAG, "P2P Invitation Sent dialog device name is null or empty."
                    + " id=" + dialogId
                    + " deviceName=" + deviceName
                    + " displayPin=" + displayPin);
        }
        addRowToP2pDialog(group, getStringId("wifi_p2p_to_message"), deviceName);

        if (displayPin != null) {
            addRowToP2pDialog(group, getStringId("wifi_p2p_show_pin_message"), displayPin);
        }

        AlertDialog dialog = new AlertDialog.Builder(
                new ContextThemeWrapper(this, getStyleId("wifi_dialog")))
                .setTitle(getString(getStringId("wifi_p2p_invitation_sent_title")))
                .setView(textEntryView)
                .setPositiveButton(getStringId("ok"), (dialogPositive, which) -> {
                    // No-op
                    if (mIsVerboseLoggingEnabled) {
                        Log.v(TAG, "P2P Invitation Sent Dialog id=" + dialogId
                                + " accepted.");
                    }
                })
                .create();
        if (mIsVerboseLoggingEnabled) {
            Log.v(TAG, "Created P2P Invitation Sent dialog."
                    + " id=" + dialogId
                    + " deviceName=" + deviceName
                    + " displayPin=" + displayPin);
        }
        return dialog;
    }

    /**
     * Returns a P2P Invitation Received Dialog for the given Intent.
     */
    private @NonNull AlertDialog createP2pInvitationReceivedDialog(
            final int dialogId,
            @Nullable final String deviceName,
            final boolean isPinRequested,
            @Nullable final String displayPin) {
        final View textEntryView = LayoutInflater.from(this)
                .inflate(getLayoutId("wifi_p2p_dialog"), null);
        ViewGroup group = textEntryView.findViewById(getViewId("info"));
        if (TextUtils.isEmpty(deviceName)) {
            Log.w(TAG, "P2P Invitation Received dialog device name is null or empty."
                    + " id=" + dialogId
                    + " deviceName=" + deviceName
                    + " displayPin=" + displayPin);
        }
        addRowToP2pDialog(group, getStringId("wifi_p2p_from_message"), deviceName);

        final EditText pinEditText;
        if (isPinRequested) {
            textEntryView.findViewById(getViewId("enter_pin_section")).setVisibility(View.VISIBLE);
            pinEditText = textEntryView.findViewById(getViewId("wifi_p2p_wps_pin"));
            pinEditText.setVisibility(View.VISIBLE);
        } else {
            pinEditText = null;
        }
        if (displayPin != null) {
            addRowToP2pDialog(group, getStringId("wifi_p2p_show_pin_message"), displayPin);
        }

        AlertDialog dialog = new AlertDialog.Builder(
                new ContextThemeWrapper(this, getStyleId("wifi_p2p_invitation_received_dialog")))
                .setTitle(getString(getStringId("wifi_p2p_invitation_to_connect_title")))
                // Set the message to "" to allow us to modify it after building (b/36913966).
                .setMessage("")
                .setView(textEntryView)
                .setPositiveButton(getStringId("accept"), (dialogPositive, which) -> {
                    String pin = null;
                    if (pinEditText != null) {
                        pin = pinEditText.getText().toString();
                    }
                    if (mIsVerboseLoggingEnabled) {
                        Log.v(TAG, "P2P Invitation Received Dialog id=" + dialogId
                                + " accepted with pin=" + pin);
                    }
                    getWifiManager().replyToP2pInvitationReceivedDialog(dialogId, true, pin);
                })
                .setNegativeButton(getStringId("decline"), (dialogNegative, which) -> {
                    if (mIsVerboseLoggingEnabled) {
                        Log.v(TAG, "P2P Invitation Received dialog id=" + dialogId
                                + " declined.");
                    }
                    getWifiManager().replyToP2pInvitationReceivedDialog(dialogId, false, null);
                })
                .setOnCancelListener((dialogCancel) -> {
                    if (mIsVerboseLoggingEnabled) {
                        Log.v(TAG, "P2P Invitation Received dialog id=" + dialogId
                                + " cancelled.");
                    }
                    getWifiManager().replyToP2pInvitationReceivedDialog(dialogId, false, null);
                })
                .create();
        if (pinEditText != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            dialog.setOnShowListener(dialogShow -> {
                Intent intent = mLaunchIntentsPerId.get(dialogId);
                if (intent != null) {
                    // Populate the pin EditText with the previous user input if we're recreating
                    // the dialog after a configuration change.
                    CharSequence previousPin =
                            intent.getCharSequenceExtra(EXTRA_DIALOG_P2P_PIN_INPUT);
                    if (previousPin != null) {
                        pinEditText.setText(previousPin);
                    }
                }
                if (getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_PORTRAIT
                        || (getResources().getConfiguration().screenLayout
                        & Configuration.SCREENLAYOUT_SIZE_MASK)
                        >= Configuration.SCREENLAYOUT_SIZE_LARGE) {
                    pinEditText.requestFocus();
                    pinEditText.setSelection(pinEditText.getText().length());
                }
                dialog.getButton(Dialog.BUTTON_POSITIVE).setEnabled(false);
            });
            pinEditText.addTextChangedListener(new TextWatcher() {
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    // No-op.
                }

                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    // No-op.
                }

                @Override
                public void afterTextChanged(Editable s) {
                    Intent intent = mLaunchIntentsPerId.get(dialogId);
                    if (intent != null) {
                        // Store the current input in the Intent in case we need to reload from a
                        // configuration change.
                        intent.putExtra(EXTRA_DIALOG_P2P_PIN_INPUT, s);
                    }
                    if (s.length() == 4 || s.length() == 8) {
                        dialog.getButton(Dialog.BUTTON_POSITIVE).setEnabled(true);
                    } else {
                        dialog.getButton(Dialog.BUTTON_POSITIVE).setEnabled(false);
                    }
                }
            });
        } else {
            dialog.setOnShowListener(dialogShow -> {
                dialog.getButton(Dialog.BUTTON_NEGATIVE).requestFocus();
            });
        }
        if ((getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_APPLIANCE)
                == Configuration.UI_MODE_TYPE_APPLIANCE) {
            // For appliance devices, add a key listener which accepts.
            dialog.setOnKeyListener((dialogKey, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
                    // TODO: Plumb this response to framework.
                    dialog.dismiss();
                    return true;
                }
                return true;
            });
        }
        if (mIsVerboseLoggingEnabled) {
            Log.v(TAG, "Created P2P Invitation Received dialog."
                    + " id=" + dialogId
                    + " deviceName=" + deviceName
                    + " isPinRequested=" + isPinRequested
                    + " displayPin=" + displayPin);
        }
        return dialog;
    }

    /**
     * Helper method to add a row to a ViewGroup for a P2P Invitation Received/Sent Dialog.
     */
    private void addRowToP2pDialog(ViewGroup group, int stringId, String value) {
        View row = LayoutInflater.from(this)
                .inflate(getLayoutId("wifi_p2p_dialog_row"), group, false);
        ((TextView) row.findViewById(getViewId("name"))).setText(getString(stringId));
        ((TextView) row.findViewById(getViewId("value"))).setText(value);
        group.addView(row);
    }
}
