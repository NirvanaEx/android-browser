// Copyright 2026 Upgrid contributors. All rights reserved.
package org.chromium.chrome.browser.upgrid;

/** Playback intent during one drag; independent of Android and asynchronous renderer replies. */
final class UpgridScrubSession {
    static final class Release {
        final int generation;
        final boolean seek;
        final double position;

        Release(int generation, boolean seek, double position) {
            this.generation = generation;
            this.seek = seek;
            this.position = position;
        }
    }

    private boolean mActive, mResumeOnEnd, mResumePending;
    private int mGeneration;
    private double mStart, mPosition, mDuration;

    boolean begin(boolean playing, double position, double duration) {
        if (mActive || !Double.isFinite(position) || !Double.isFinite(duration) || duration <= 0)
            return false;
        mResumeOnEnd = mResumePending || playing;
        mResumePending = false;
        ++mGeneration;
        mDuration = duration;
        mStart = mPosition = Math.max(0, Math.min(duration, position));
        mActive = true;
        return true;
    }

    boolean isActive() {
        return mActive;
    }

    boolean shouldPause() {
        return mActive && mResumeOnEnd;
    }

    double start() {
        return mStart;
    }

    double position() {
        return mPosition;
    }

    double duration() {
        return mDuration;
    }

    void preview(double position) {
        if (mActive && Double.isFinite(position))
            mPosition = Math.max(0, Math.min(mDuration, position));
    }

    Release release(boolean commit) {
        if (!mActive) return null;
        mActive = false;
        mResumePending = mResumeOnEnd;
        return new Release(mGeneration, commit, mPosition);
    }

    boolean isCurrent(Release release) {
        return release.generation == mGeneration;
    }

    boolean takeResume(Release release) {
        if (!isCurrent(release) || mActive || !mResumePending) return false;
        mResumePending = mResumeOnEnd = false;
        return true;
    }

    void onPlaybackCommand() {
        ++mGeneration;
        mResumePending = mResumeOnEnd = false;
    }

    boolean close(boolean background) {
        boolean restore = !background && (mActive ? mResumeOnEnd : mResumePending);
        ++mGeneration;
        mActive = mResumePending = mResumeOnEnd = false;
        return restore;
    }
}
