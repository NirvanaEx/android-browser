// Copyright 2026 Upgrid contributors. All rights reserved.
package org.chromium.chrome.browser.upgrid;

import android.app.Activity;
import android.content.pm.ActivityInfo;

import java.util.WeakHashMap;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.content_public.browser.ScreenOrientationProvider;
import org.chromium.ui.base.WindowAndroid;

/** Keeps fullscreen and its control dialog under one manual orientation lock. */
@NullMarked
public final class UpgridOrientationLock implements AutoCloseable {
    private static final WeakHashMap<Activity, State> STATES = new WeakHashMap<>();

    private static final class State {
        final int originalOrientation;
        final @Nullable WindowAndroid window;
        int references;

        State(Activity activity, @Nullable WindowAndroid window) {
            originalOrientation = activity.getRequestedOrientation();
            this.window = window;
        }
    }

    private final Activity mActivity;
    private final State mState;
    private boolean mClosed;

    private UpgridOrientationLock(Activity activity, State state) {
        mActivity = activity;
        mState = state;
        ++state.references;
    }

    public static UpgridOrientationLock acquire(Activity activity, @Nullable WindowAndroid window) {
        State state = STATES.get(activity);
        if (state == null) {
            state = new State(activity, window);
            STATES.put(activity, state);
            if (window != null) {
                ScreenOrientationProvider.getInstance().setUserControlledOrientation(window, true);
            }
            // LOCKED preserves the current physical orientation, even with OS auto-rotate enabled.
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        }
        return new UpgridOrientationLock(activity, state);
    }

    @Override
    public void close() {
        if (mClosed) return;
        mClosed = true;
        if (--mState.references != 0 || STATES.get(mActivity) != mState) return;
        STATES.remove(mActivity);
        if (mState.window != null) {
            ScreenOrientationProvider.getInstance()
                    .setUserControlledOrientation(mState.window, false);
        }
        if (!mActivity.isDestroyed()) {
            mActivity.setRequestedOrientation(mState.originalOrientation);
        }
    }
}
