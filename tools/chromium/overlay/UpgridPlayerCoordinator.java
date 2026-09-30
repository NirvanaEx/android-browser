// Copyright 2026 Upgrid contributors. All rights reserved.
package org.chromium.chrome.browser.upgrid;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.function.Supplier;

import org.chromium.base.Log;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.fullscreen.FullscreenManager;
import org.chromium.chrome.browser.fullscreen.FullscreenOptions;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content_public.browser.WebContents;

/** Connects browser input and successful site fullscreen to the same player. */
@NullMarked
public final class UpgridPlayerCoordinator implements FullscreenManager.Observer {
    private final Activity mActivity;
    private final FullscreenManager mFullscreen;
    private final Supplier<@Nullable Tab> mCurrentTab;
    private final @Nullable View mButton;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mGeneration;
    private boolean mDestroyed;
    private @Nullable UpgridOrientationLock mOrientationLock;

    public UpgridPlayerCoordinator(
            Activity activity, FullscreenManager fullscreen, Supplier<@Nullable Tab> currentTab) {
        mActivity = activity;
        mFullscreen = fullscreen;
        mCurrentTab = currentTab;
        mButton = activity.findViewById(org.chromium.chrome.browser.toolbar.R.id.upgrid_player_button);
        if (mButton != null) {
            mButton.setOnClickListener(view -> {
                Log.i("UpgridPlayer", "Toolbar button pressed");
                Tab tab = mCurrentTab.get();
                if (tab != null) UpgridPlayer.open(mActivity, tab);
            });
        }
        mFullscreen.addObserver(this);
        Tab tab = mCurrentTab.get();
        if (tab != null && mFullscreen.getPersistentFullscreenMode()) {
            lockOrientation(tab);
            int generation = ++mGeneration;
            mHandler.post(() -> tryAttach(tab, generation, 0));
        }
    }

    @Override
    public void onEnterFullscreen(Tab tab, FullscreenOptions options) {
        if (mDestroyed || mCurrentTab.get() != tab || mActivity.isFinishing()
                || mActivity.isDestroyed()) return;
        // Take the lock synchronously, before the site's fullscreenchange
        // handler or Chromium's native controls can request a rotation.
        lockOrientation(tab);
        int generation = ++mGeneration;
        // The browser hides its controls before Blink confirms fullscreen. Wait
        // for fullscreen; Blink then confirms the video inside that exact root.
        // hasActiveEffectivelyFullscreenVideo() excludes paused videos.
        mHandler.post(() -> tryAttach(tab, generation, 0));
    }

    private void lockOrientation(Tab tab) {
        WebContents contents = tab.getWebContents();
        if (mOrientationLock == null && contents != null && !contents.isDestroyed()) {
            mOrientationLock = UpgridOrientationLock.acquire(
                    mActivity, contents.getTopLevelNativeWindow());
        }
    }

    private void releaseOrientation() {
        if (mOrientationLock == null) return;
        mOrientationLock.close();
        mOrientationLock = null;
    }

    private void tryAttach(Tab tab, int generation, int attempt) {
        if (mDestroyed || generation != mGeneration || mCurrentTab.get() != tab
                || !tab.isUserInteractable()
                || mActivity.isFinishing() || mActivity.isDestroyed()
                || !mFullscreen.getPersistentFullscreenMode() || UpgridPlayer.isActive(mActivity)) {
            return;
        }
        WebContents contents = tab.getWebContents();
        if (contents == null || contents.isDestroyed()) return;
        if (contents.isFullscreenForCurrentTab()) {
            UpgridPlayer.adoptFullscreen(mActivity, tab);
        } else if (attempt < 20) {
            mHandler.postDelayed(() -> tryAttach(tab, generation, attempt + 1), 100);
        }
    }

    @Override
    public void onExitFullscreen(Tab tab) {
        ++mGeneration;
        mHandler.removeCallbacksAndMessages(null);
        releaseOrientation();
    }

    public void destroy() {
        mDestroyed = true;
        ++mGeneration;
        mHandler.removeCallbacksAndMessages(null);
        mFullscreen.removeObserver(this);
        if (mButton != null) mButton.setOnClickListener(null);
        UpgridPlayer.closeForActivity(mActivity);
        releaseOrientation();
    }
}
