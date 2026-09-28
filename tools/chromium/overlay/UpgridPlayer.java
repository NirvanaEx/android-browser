// Copyright 2026 Upgrid contributors. All rights reserved.
// Upgrid Chromium development overlay.
package org.chromium.chrome.browser.upgrid;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.jni_zero.JniType;
import org.jni_zero.NativeMethods;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.Callback;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.tab.EmptyTabObserver;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content_public.browser.ScreenOrientationProvider;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.WindowAndroid;

import java.util.Locale;
import java.util.WeakHashMap;

/** Native controls over the existing Chromium video surface, without a second decoder. */
public final class UpgridPlayer implements Application.ActivityLifecycleCallbacks {
    // Runtime-only menu ID, outside Android's generated resource ID space.
    public static final int MENU_ID = 0x00f04701;
    private static final WeakHashMap<Activity, UpgridPlayer> ACTIVE = new WeakHashMap<>();
    private static final int PLAY = 0, PAUSE = 1, SEEK = 2, RATE = 3, MUTE = 4, LOOP = 5;
    private static final int FIT = 6, CAPTIONS = 9, DOWNLOAD = 10, CAST = 11, PIP = 12;
    private final Activity mActivity;
    private final Tab mTab;
    private final WebContents mContents;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final long mStarted = SystemClock.uptimeMillis();
    private final int mOriginalOrientation;
    private final WindowAndroid mWindow;
    private final EmptyTabObserver mObserver =
            new EmptyTabObserver() {
                @Override
                public void onHidden(Tab tab, int reason) {
                    close(true);
                }

                @Override
                public void onDestroyed(Tab tab) {
                    close(true);
                }

                @Override
                public void onContentChanged(Tab tab) {
                    close(true);
                }
            };
    private Dialog mDialog;
    private AlertDialog mOptionsDialog;
    private LinearLayout mTop, mBottom;
    private Button mPlay, mLoop, mMute, mRate, mFit, mUnlock;
    private SeekBar mSeek;
    private TextView mTime;
    private TextView mGestureStatus;
    private ProgressBar mBuffering;
    private float mStartBrightness;
    private int mStartVolume;
    private int mGestureAxis;
    private final UpgridScrubSession mScrub = new UpgridScrubSession();
    private final Runnable mHideGesture =
            () -> {
                if (mGestureStatus != null) mGestureStatus.setVisibility(View.GONE);
            };
    private JSONObject mState;
    private boolean mClosed, mControls = true, mHasFullscreen;
    private boolean mLocked;
    private int mFitMode;
    private long mLastResponse;
    private final Runnable mHide = () -> setControls(false);
    private final Runnable mWatchdog =
            new Runnable() {
                @Override
                public void run() {
                    if (mClosed) return;
                    if (SystemClock.uptimeMillis() - mLastResponse > 5000) {
                        fail("Плеер не отвечает");
                    } else {
                        mHandler.postDelayed(this, 1000);
                    }
                }
            };

    public static void open(Activity activity, Tab tab) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        UpgridPlayer previous = ACTIVE.get(activity);
        if (previous != null) previous.close(false);
        if (tab == null || tab.getWebContents() == null) return;
        UpgridPlayer player = new UpgridPlayer(activity, tab);
        ACTIVE.put(activity, player);
        player.start();
    }

    private UpgridPlayer(Activity activity, Tab tab) {
        mActivity = activity;
        mTab = tab;
        mContents = tab.getWebContents();
        mWindow = mContents.getTopLevelNativeWindow();
        mOriginalOrientation = activity.getRequestedOrientation();
    }

    private void start() {
        mLastResponse = mStarted;
        mTab.addObserver(mObserver);
        mActivity.getApplication().registerActivityLifecycleCallbacks(this);
        if (mWindow != null)
            ScreenOrientationProvider.getInstance().setUserControlledOrientation(mWindow, true);
        // Freeze the current orientation; only the rotation button changes it.
        mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        mHandler.postDelayed(mWatchdog, 1000);
        UpgridPlayerJni.get().open(mContents, this::onPoll);
    }

    private void onPoll(String value) {
        if (!receive(value)) return;
        mHandler.postDelayed(
                () -> {
                    if (!mClosed && !mContents.isDestroyed()) {
                        UpgridPlayerJni.get().read(mContents, this::onPoll);
                    }
                },
                250);
    }

    private boolean receive(String value) {
        if (mClosed) return false;
        mLastResponse = SystemClock.uptimeMillis();
        try {
            JSONObject state = new JSONObject(value);
            if (!state.optBoolean("ok")) {
                fail("Видео недоступно. Вернитесь к странице и выберите видео.");
                return false;
            }
            if (state.optBoolean("pipRequested")) {
                close(false);
                return false;
            }
            if (!state.optBoolean("fullscreen")) {
                if (mHasFullscreen) {
                    close(false);
                    return false;
                }
                if (mLastResponse - mStarted > 4000) {
                    fail("Не удалось перейти в полноэкранный режим");
                    return false;
                }
                return true;
            }
            mState = state;
            mHasFullscreen = true;
            if (mDialog == null) createControls();
            render();
            return true;
        } catch (JSONException e) {
            fail("Не удалось открыть плеер");
            return false;
        }
    }

    private void command(int command, double value) {
        if (mClosed || mContents.isDestroyed()) return;
        if (command == PLAY || command == PAUSE) {
            mScrub.onPlaybackCommand();
        }
        showControls();
        UpgridPlayerJni.get().control(mContents, command, value, this::receiveCommand);
    }

    private void receiveCommand(String state) {
        receive(state);
    }

    private void createControls() {
        mDialog = new Dialog(mActivity, R.style.Theme_Chromium_Activity_Fullscreen_Transparent);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mDialog.setOnCancelListener(dialog -> close(false));
        FrameLayout root = new FrameLayout(mActivity);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setContentDescription("Видеоплеер Upgrid");
        mTop = row();
        mTop.addView(button("Назад", () -> close(false)));
        mTop.addView(
                button(
                        "Поворот",
                        () -> {
                            boolean landscape = root.getWidth() > root.getHeight();
                            mActivity.setRequestedOrientation(
                                    landscape
                                            ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                            : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                        }));
        mMute = button("Звук", () -> command(MUTE, mState.optBoolean("muted") ? 0 : 1));
        mTop.addView(mMute);
        mLoop = button("Повтор", () -> command(LOOP, mState.optBoolean("loop") ? 0 : 1));
        mTop.addView(mLoop);
        mTop.addView(
                button(
                        "Скачать",
                        () -> {
                            if (!mState.optBoolean("canDownload")) {
                                Toast.makeText(
                                                mActivity,
                                                "Этот поток нельзя скачать как видеофайл",
                                                Toast.LENGTH_SHORT)
                                        .show();
                                return;
                            }
                            UpgridPlayerJni.get()
                                    .control(
                                            mContents,
                                            DOWNLOAD,
                                            0,
                                            value -> {
                                                if (receive(value)) close(false);
                                            });
                        }));
        mTop.addView(
                button(
                        "Замок",
                        () -> {
                            mLocked = true;
                            setControls(false);
                        }));
        mTop.addView(
                button(
                        "Ещё",
                        () -> {
                            showOptions(
                                    new AlertDialog.Builder(mActivity)
                                            .setItems(
                                                    new String[] {
                                                        "Картинка в картинке",
                                                        "Трансляция",
                                                        "Качество YouTube"
                                                    },
                                                    (dialog, index) -> {
                                                        if (index == 2) {
                                                            showSiteOptions(false);
                                                            return;
                                                        }
                                                        String capability =
                                                                index == 0 ? "canPip" : "canCast";
                                                        if (!mState.optBoolean(capability)) {
                                                            Toast.makeText(
                                                                            mActivity,
                                                                            "Недоступно для этого"
                                                                                    + " видео или"
                                                                                    + " устройства",
                                                                            Toast.LENGTH_SHORT)
                                                                    .show();
                                                            return;
                                                        }
                                                        UpgridPlayerJni.get()
                                                                .control(
                                                                        mContents,
                                                                        index == 0 ? PIP : CAST,
                                                                        0,
                                                                        value -> {
                                                                            if (receive(value)
                                                                                    && index == 1)
                                                                                close(false);
                                                                        });
                                                    }));
                        }));
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(mTop, top);
        mUnlock =
                button(
                        "Разблокировать",
                        () -> {
                            mLocked = false;
                            showControls();
                        });
        mUnlock.setVisibility(View.GONE);
        root.addView(
                mUnlock, new FrameLayout.LayoutParams(dp(156), dp(56), Gravity.TOP | Gravity.END));

        mBottom = new LinearLayout(mActivity);
        mBottom.setOrientation(LinearLayout.VERTICAL);
        mBottom.setBackgroundColor(0xb0000000);
        mTime = new TextView(mActivity);
        mTime.setTextColor(Color.WHITE);
        mTime.setPadding(dp(16), dp(4), dp(16), 0);
        mBottom.addView(mTime);
        mSeek = new SeekBar(mActivity);
        mSeek.setMax(10000);
        mSeek.setContentDescription("Позиция видео");
        mSeek.setOnTouchListener(
                (view, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) finishScrub(false);
                    return false;
                });
        mSeek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {
                        beginScrub();
                    }

                    @Override
                    public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                        if (user && mScrub.isActive())
                            previewScrub(mScrub.duration() * progress / 10000.0);
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {
                        finishScrub(true);
                    }
                });
        mBottom.addView(mSeek);
        LinearLayout actions = row();
        mPlay = button("Пауза", () -> command(mState.optBoolean("paused") ? PLAY : PAUSE, 0));
        actions.addView(mPlay);
        mRate =
                button(
                        "1×",
                        () -> {
                            String[] titles = {
                                "0.25×", "0.5×", "0.75×", "1×", "1.25×", "1.5×", "1.75×", "2×"
                            };
                            showOptions(
                                    new AlertDialog.Builder(mActivity)
                                            .setTitle("Скорость")
                                            .setItems(
                                                    titles,
                                                    (dialog, index) ->
                                                            command(RATE, (index + 1) * 0.25)));
                        });
        actions.addView(mRate);
        mFit =
                button(
                        "Вписать",
                        () -> {
                            mFitMode = (mFitMode + 1) % 3;
                            command(FIT + mFitMode, 0);
                        });
        actions.addView(mFit);
        actions.addView(
                button(
                        "Субтитры",
                        () -> {
                            JSONArray tracks = mState.optJSONArray("tracks");
                            int count = tracks == null ? 0 : tracks.length();
                            if (count == 0) {
                                showSiteOptions(true);
                                return;
                            }
                            String[] labels = new String[count + 1];
                            labels[0] = "Выключить";
                            for (int i = 0; i < count; i++)
                                labels[i + 1] = tracks.optJSONObject(i).optString("label");
                            showOptions(
                                    new AlertDialog.Builder(mActivity)
                                            .setTitle("Субтитры")
                                            .setItems(
                                                    labels,
                                                    (dialog, index) ->
                                                            command(
                                                                    CAPTIONS,
                                                                    index == 0
                                                                            ? 0
                                                                            : tracks.optJSONObject(
                                                                                            index
                                                                                                    - 1)
                                                                                    .optInt(
                                                                                            "id"))));
                        }));
        mBottom.addView(actions);
        root.addView(mBottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        mBuffering = new ProgressBar(mActivity);
        mBuffering.setContentDescription("Буферизация видео");
        mBuffering.setVisibility(View.GONE);
        root.addView(mBuffering, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        mGestureStatus = new TextView(mActivity);
        mGestureStatus.setTextColor(Color.WHITE);
        mGestureStatus.setTextSize(18);
        mGestureStatus.setPadding(dp(20), dp(12), dp(20), dp(12));
        mGestureStatus.setBackgroundColor(0xb0000000);
        mGestureStatus.setVisibility(View.GONE);
        root.addView(mGestureStatus, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        GestureDetector gestures =
                new GestureDetector(
                        mActivity,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onDown(MotionEvent event) {
                                mGestureAxis = 0;
                                mStartBrightness =
                                        mDialog.getWindow().getAttributes().screenBrightness;
                                if (mStartBrightness < 0) {
                                    try {
                                        mStartBrightness =
                                                Settings.System.getInt(
                                                                mActivity.getContentResolver(),
                                                                Settings.System.SCREEN_BRIGHTNESS,
                                                                128)
                                                        / 255f;
                                    } catch (SecurityException ignored) {
                                        mStartBrightness = 0.5f;
                                    }
                                }
                                AudioManager audio =
                                        (AudioManager)
                                                mActivity.getSystemService(Context.AUDIO_SERVICE);
                                mStartVolume =
                                        audio == null
                                                ? 0
                                                : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                                return true;
                            }

                            @Override
                            public boolean onSingleTapConfirmed(MotionEvent event) {
                                setControls(!mControls);
                                return true;
                            }

                            @Override
                            public boolean onDoubleTap(MotionEvent event) {
                                if (mLocked) return true;
                                boolean back = event.getX() < root.getWidth() / 2f;
                                command(SEEK, mState.optDouble("position") + (back ? -5 : 5));
                                showGestureStatus(back ? "−5 секунд" : "+5 секунд");
                                return true;
                            }

                            @Override
                            public boolean onScroll(
                                    MotionEvent first, MotionEvent event, float dx, float dy) {
                                if (mLocked) return true;
                                if (first == null) return false;
                                if (mGestureAxis == 0) {
                                    if (Math.abs(event.getX() - first.getX())
                                            > Math.abs(event.getY() - first.getY())) {
                                        if (mState.optDouble("duration") <= 0) return false;
                                        mGestureAxis = 3;
                                        beginScrub();
                                    } else {
                                        mGestureAxis = first.getX() < root.getWidth() / 2f ? 1 : 2;
                                    }
                                }
                                if (mGestureAxis == 3) {
                                    previewScrub(
                                            mScrub.start()
                                                    + (event.getX() - first.getX())
                                                            / Math.max(1, root.getWidth())
                                                            * mScrub.duration());
                                    return true;
                                }
                                float fraction =
                                        (first.getY() - event.getY())
                                                / Math.max(1, root.getHeight());
                                if (mGestureAxis == 1) {
                                    WindowManager.LayoutParams params =
                                            mDialog.getWindow().getAttributes();
                                    params.screenBrightness =
                                            Math.max(
                                                    0.02f,
                                                    Math.min(1f, mStartBrightness + fraction));
                                    mDialog.getWindow().setAttributes(params);
                                    showGestureStatus(
                                            "Яркость "
                                                    + Math.round(params.screenBrightness * 100)
                                                    + "%");
                                } else {
                                    AudioManager audio =
                                            (AudioManager)
                                                    mActivity.getSystemService(
                                                            Context.AUDIO_SERVICE);
                                    if (audio == null) return false;
                                    int maximum =
                                            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                                    int level =
                                            Math.max(
                                                    0,
                                                    Math.min(
                                                            maximum,
                                                            Math.round(
                                                                    mStartVolume
                                                                            + fraction * maximum)));
                                    if (level != audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
                                    showGestureStatus(
                                            "Громкость "
                                                    + Math.round(
                                                            100f * level / Math.max(1, maximum))
                                                    + "%");
                                }
                                return true;
                            }
                        });
        root.setOnTouchListener(
                (view, event) -> {
                    boolean handled = gestures.onTouchEvent(event);
                    if (event.getActionMasked() == MotionEvent.ACTION_UP
                            || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        if (mGestureAxis == 3) {
                            finishScrub(event.getActionMasked() == MotionEvent.ACTION_UP);
                            handled = true;
                        }
                        mGestureAxis = 0;
                    }
                    return handled;
                });
        mDialog.setContentView(root);
        Window window = mDialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setFormat(PixelFormat.TRANSLUCENT);
        window.setWindowAnimations(0);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.getDecorView()
                .setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        mDialog.show();
        window.setLayout(-1, -1);
        showControls();
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(0xb0000000);
        return row;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(mActivity);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setMaxLines(2);
        button.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(0x40ffffff),
                        new ColorDrawable(Color.TRANSPARENT),
                        new ColorDrawable(Color.WHITE)));
        button.setLayoutParams(new LinearLayout.LayoutParams(0, dp(56), 1));
        button.setOnClickListener(
                view -> {
                    showControls();
                    action.run();
                });
        return button;
    }

    private void showGestureStatus(String text) {
        mGestureStatus.setText(text);
        mGestureStatus.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mHideGesture);
        mHandler.postDelayed(mHideGesture, 800);
    }

    private void beginScrub() {
        if (mClosed
                || !mScrub.begin(
                        !mState.optBoolean("paused") && !mState.optBoolean("ended"),
                        mState.optDouble("position"),
                        mState.optDouble("duration"))) return;
        showControls();
        if (mScrub.shouldPause())
            UpgridPlayerJni.get().control(mContents, PAUSE, 0, this::receiveCommand);
    }

    private void previewScrub(double position) {
        if (!mScrub.isActive() || mClosed) return;
        mScrub.preview(position);
        mSeek.setProgress((int) (10000 * mScrub.position() / Math.max(1, mScrub.duration())));
        mTime.setText(time(mScrub.position()) + " / " + time(mScrub.duration()));
        showGestureStatus(time(mScrub.position()));
    }

    private void finishScrub(boolean commit) {
        if (mClosed || mContents.isDestroyed()) return;
        UpgridScrubSession.Release release = mScrub.release(commit);
        if (release == null) return;
        // Seek once at release, then restore playback only after the renderer
        // confirms the same selected source. Cancellation does not change time.
        if (release.seek) {
            UpgridPlayerJni.get()
                    .control(
                            mContents,
                            SEEK,
                            release.position,
                            value -> {
                                if (mScrub.isCurrent(release)
                                        && receive(value)
                                        && mScrub.takeResume(release)) command(PLAY, 0);
                            });
        } else if (mScrub.takeResume(release)) {
            command(PLAY, 0);
        }
        showControls();
    }

    private void showOptions(AlertDialog.Builder builder) {
        if (mClosed) return;
        if (mOptionsDialog != null) mOptionsDialog.dismiss();
        AlertDialog options = builder.create();
        mOptionsDialog = options;
        options.setOnDismissListener(
                dialog -> {
                    if (mOptionsDialog != options) return;
                    mOptionsDialog = null;
                    if (!mClosed) showControls();
                });
        mHandler.removeCallbacks(mHide);
        options.show();
    }

    private void showSiteOptions(boolean captions) {
        if (mClosed || mContents.isDestroyed()) return;
        UpgridPlayerJni.get()
                .siteOptions(
                        mContents,
                        "list",
                        "",
                        value -> {
                            if (mClosed) return;
                            try {
                                JSONObject response = new JSONObject(value);
                                JSONArray choices =
                                        response.optJSONArray(captions ? "captions" : "qualities");
                                if (!response.optBoolean("ok")
                                        || choices == null
                                        || choices.length() == 0) {
                                    Toast.makeText(
                                                    mActivity,
                                                    "Сайт не предоставил доступные варианты",
                                                    Toast.LENGTH_SHORT)
                                            .show();
                                    return;
                                }
                                int offset = captions ? 1 : 0;
                                String[] labels = new String[choices.length() + offset];
                                String[] ids = new String[labels.length];
                                if (captions) {
                                    labels[0] = "Выключить";
                                    ids[0] = "";
                                }
                                for (int i = 0; i < choices.length(); i++) {
                                    labels[i + offset] =
                                            captions
                                                    ? choices.getJSONObject(i).getString("label")
                                                    : choices.getString(i);
                                    ids[i + offset] =
                                            captions
                                                    ? choices.getJSONObject(i).getString("id")
                                                    : choices.getString(i);
                                }
                                showOptions(
                                        new AlertDialog.Builder(mActivity)
                                                .setTitle(
                                                        captions
                                                                ? "Субтитры YouTube"
                                                                : "Качество YouTube")
                                                .setItems(
                                                        labels,
                                                        (dialog, index) -> {
                                                            if (mClosed || mContents.isDestroyed())
                                                                return;
                                                            UpgridPlayerJni.get()
                                                                    .siteOptions(
                                                                            mContents,
                                                                            captions
                                                                                    ? "caption"
                                                                                    : "quality",
                                                                            ids[index],
                                                                            result -> {
                                                                                if (mClosed) return;
                                                                                try {
                                                                                    if (new JSONObject(
                                                                                                    result)
                                                                                            .optBoolean(
                                                                                                    "ok"))
                                                                                        return;
                                                                                } catch (
                                                                                        JSONException
                                                                                                ignored) {
                                                                                }
                                                                                Toast.makeText(
                                                                                                mActivity,
                                                                                                "Сайт не"
                                                                                                    + " применил"
                                                                                                    + " выбранный"
                                                                                                    + " вариант",
                                                                                                Toast
                                                                                                        .LENGTH_SHORT)
                                                                                        .show();
                                                                            });
                                                        }));
                            } catch (JSONException error) {
                                Toast.makeText(
                                                mActivity,
                                                "Не удалось получить настройки видео",
                                                Toast.LENGTH_SHORT)
                                        .show();
                            }
                        });
    }

    private void render() {
        mBuffering.setVisibility(mState.optBoolean("buffering") ? View.VISIBLE : View.GONE);
        double duration = mState.optDouble("duration"), position = mState.optDouble("position");
        mPlay.setText(mState.optBoolean("paused") ? "Играть" : "Пауза");
        mLoop.setText(mState.optBoolean("loop") ? "Повтор ✓" : "Повтор");
        mMute.setText(mState.optBoolean("muted") ? "Без звука" : "Звук");
        mRate.setText(String.format(Locale.ROOT, "%s×", mState.optDouble("rate", 1)));
        mFit.setText(new String[] {"Вписать", "Заполнить", "Растянуть"}[mFitMode]);
        if (!mScrub.isActive())
            mTime.setText(
                    time(position) + (duration > 0 ? " / " + time(duration) : " · Прямой эфир"));
        mSeek.setEnabled(duration > 0);
        if (!mScrub.isActive())
            mSeek.setProgress(duration > 0 ? (int) (10000 * position / duration) : 0);
        Window window = mDialog.getWindow();
        if (mState.optBoolean("paused"))
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private static String time(double value) {
        int seconds = Math.max(0, (int) value);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    private void showControls() {
        setControls(true);
    }

    private void setControls(boolean visible) {
        if (mLocked) visible = false;
        mControls = visible;
        if (mTop == null) return;
        mDialog.setCancelable(!mLocked);
        mTop.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        mBottom.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        mUnlock.setVisibility(mLocked ? View.VISIBLE : View.GONE);
        mHandler.removeCallbacks(mHide);
        if (visible && !mScrub.isActive() && mOptionsDialog == null)
            mHandler.postDelayed(mHide, 5000);
    }

    private void fail(String message) {
        if (!mClosed) Toast.makeText(mActivity, message, Toast.LENGTH_LONG).show();
        close(false);
    }

    private void close(boolean pause) {
        if (mClosed) return;
        boolean restoreScrubPlayback = mScrub.close(pause);
        mClosed = true;
        mHandler.removeCallbacksAndMessages(null);
        mTab.removeObserver(mObserver);
        mActivity.getApplication().unregisterActivityLifecycleCallbacks(this);
        if (!mContents.isDestroyed()) {
            // Back during a scrub restores the state before our temporary pause.
            // Background cancellation keeps it paused, as for ordinary playback.
            if (restoreScrubPlayback)
                UpgridPlayerJni.get().control(mContents, PLAY, 0, ignored -> {});
            UpgridPlayerJni.get().close(mContents, pause);
        }
        if (mOptionsDialog != null) mOptionsDialog.dismiss();
        if (mDialog != null) mDialog.dismiss();
        if (mWindow != null)
            ScreenOrientationProvider.getInstance().setUserControlledOrientation(mWindow, false);
        mActivity.setRequestedOrientation(mOriginalOrientation);
        if (ACTIVE.get(mActivity) == this) ACTIVE.remove(mActivity);
    }

    @Override
    public void onActivityPaused(Activity activity) {
        if (activity == mActivity) close(true);
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (activity == mActivity) close(true);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle state) {}

    @Override
    public void onActivityStarted(Activity activity) {}

    @Override
    public void onActivityResumed(Activity activity) {}

    @Override
    public void onActivityStopped(Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

    @NativeMethods
    interface Natives {
        void open(
                @JniType("content::WebContents*") WebContents contents, Callback<String> callback);

        void read(
                @JniType("content::WebContents*") WebContents contents, Callback<String> callback);

        void control(
                @JniType("content::WebContents*") WebContents contents,
                int command,
                double value,
                Callback<String> callback);

        void close(@JniType("content::WebContents*") WebContents contents, boolean pause);

        void siteOptions(
                @JniType("content::WebContents*") WebContents contents,
                @JniType("std::string") String command,
                @JniType("std::string") String option,
                Callback<String> callback);
    }
}
