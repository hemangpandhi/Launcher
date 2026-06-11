/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.carlauncher;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.car.settings.CarSettings.Secure.KEY_UNACCEPTED_TOS_DISABLED_APPS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

import static com.android.car.carlauncher.AppGridFragment.Mode.ALL_APPS;
import static com.android.car.carlauncher.CarLauncherViewModel.CarLauncherViewModelFactory;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.TaskStackListener;
import android.car.Car;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import android.animation.ValueAnimator;
import android.animation.ObjectAnimator;
import android.animation.AnimatorSet;
import android.animation.PropertyValuesHolder;
import android.animation.ArgbEvaluator;
import android.graphics.drawable.GradientDrawable;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.collection.ArraySet;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.carlauncher.homescreen.HomeCardModule;
import com.android.car.carlauncher.homescreen.audio.IntentHandler;
import com.android.car.carlauncher.homescreen.audio.MediaLaunchHandler;
import com.android.car.carlauncher.homescreen.audio.dialer.InCallIntentRouter;
import com.android.car.carlauncher.homescreen.audio.media.MediaLaunchRouter;
import com.android.car.carlauncher.taskstack.TaskStackChangeListeners;
import com.android.car.internal.common.UserHelperLite;
import com.android.car.media.common.source.MediaSource;
import com.android.wm.shell.taskview.TaskView;

import com.google.common.annotations.VisibleForTesting;

import java.util.Set;

/**
 * Basic Launcher for Android Automotive which demonstrates the use of {@link TaskView} to host
 * maps content and uses a Model-View-Presenter structure to display content in cards.
 *
 * <p>Implementations of the Launcher that use the given layout of the main activity
 * (car_launcher.xml) can customize the home screen cards by providing their own
 * {@link HomeCardModule} for R.id.top_card or R.id.bottom_card. Otherwise, implementations that
 * use their own layout should define their own activity rather than using this one.
 *
 * <p>Note: On some devices, the TaskView may render with a width, height, and/or aspect
 * ratio that does not meet Android compatibility definitions. Developers should work with content
 * owners to ensure content renders correctly when extending or emulating this class.
 */
public class CarLauncher extends FragmentActivity {
    public static final String TAG = "CarLauncher";
    public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private Car mCar;

    /** Set to {@code true} once we've logged that the Activity is fully drawn. */
    private boolean mIsReadyLogged;

    @VisibleForTesting
    ContentObserver mTosContentObserver;

    private final IntentHandler mIntentHandler = new IntentHandler() {
        @Override
        public void handleIntent(Intent intent) {
            if (intent != null) {
                ActivityOptions options = ActivityOptions.makeBasic();
                startActivity(intent, options.toBundle());
            }
        }
    };

    // Used instead of IntentHandler because media apps may provide a PendingIntent instead
    private final MediaLaunchHandler mMediaMediaLaunchHandler = new MediaLaunchHandler() {
        @Override
        public void handleLaunchMedia(@NonNull MediaSource mediaSource) {
            if (DEBUG) {
                Log.d(TAG, "Launching media source " + mediaSource);
            }
            mediaSource.launchActivity(CarLauncher.this, ActivityOptions.makeBasic());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (DEBUG) {
            Log.d(TAG, "onCreate(" + getUserId() + ") displayId=" + getDisplayId());
        }
        getTheme().applyStyle(R.style.CarLauncherActivityThemeOverlay, true);
        // Since MUMD/MUPAND is introduced, CarLauncher can be called in the main display of
        // visible background users.
        // For Passenger scenarios, replace the maps_card with AppGridActivity, as currently
        // there is no maps use-case for passengers.
        UserManager um = getSystemService(UserManager.class);
        boolean isPassengerDisplay = getDisplayId() != Display.DEFAULT_DISPLAY
                || um.isVisibleBackgroundUsersOnDefaultDisplaySupported();

        // Don't show the maps panel in multi window mode.
        // NOTE: CTS tests for split screen are not compatible with activity views on the default
        // activity of the launcher
        if (isInMultiWindowMode() || isInPictureInPictureMode()) {
            setContentView(R.layout.car_launcher_multiwindow);
        } else {
            setContentView(R.layout.car_launcher);
            if (isPassengerDisplay) {
                // For Passenger display show the AppGridFragment
                getSupportFragmentManager().beginTransaction().replace(android.R.id.content,
                        AppGridFragment.newInstance(ALL_APPS)).commit();
            }
        }

        MediaLaunchRouter.getInstance().registerMediaLaunchHandler(mMediaMediaLaunchHandler);
        InCallIntentRouter.getInstance().registerInCallIntentHandler(mIntentHandler);

        setupContentObserversForTos();
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeLogReady();
    }

    private void animateGridEntrance() {
    }

    private void startAmbientAnimations() {
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterTosContentObserver();
        release();
    }

    private void unregisterTosContentObserver() {
        if (mTosContentObserver != null) {
            Log.i(TAG, "Unregister content observer for tos state");
            getContentResolver().unregisterContentObserver(mTosContentObserver);
            mTosContentObserver = null;
        }
    }



    private void release() {
        if (mCar != null) {
            mCar.disconnect();
            mCar = null;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    /** Logs that the Activity is ready. Used for startup time diagnostics. */
    private void maybeLogReady() {
        boolean isResumed = isResumed();
        if (isResumed) {
            // We should report every time - the Android framework will take care of logging just
            // when it's effectively drawn for the first time, but....
            reportFullyDrawn();
            if (!mIsReadyLogged) {
                // ... we want to manually check that the Log.i below (which is useful to show
                // the user id) is only logged once (otherwise it would be logged every time the
                // user taps Home)
                Log.i(TAG, "Launcher for user " + getUserId() + " is ready");
                mIsReadyLogged = true;
            }
        }
    }





    private void setupContentObserversForTos() {
        if (AppLauncherUtils.tosStatusUninitialized(/* context = */ this)
                || !AppLauncherUtils.tosAccepted(/* context = */ this)) {
            Log.i(TAG, "TOS not accepted, setting up content observers for TOS state");
        } else {
            Log.i(TAG,
                    "TOS accepted, state will remain accepted, don't need to observe this value");
            return;
        }
        mTosContentObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                boolean tosAccepted = AppLauncherUtils.tosAccepted(getBaseContext());
                Log.i(TAG, "TOS state updated:" + tosAccepted);
                if (tosAccepted) {
                    unregisterTosContentObserver();
                }
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(KEY_UNACCEPTED_TOS_DISABLED_APPS),
                /* notifyForDescendants*/ false,
                mTosContentObserver);
    }
}
