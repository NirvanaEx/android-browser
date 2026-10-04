// Copyright 2026 Upgrid contributors. All rights reserved.
// Upgrid Chromium development overlay.
package org.chromium.chrome.browser.upgrid;

import android.annotation.SuppressLint;
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
import android.widget.ImageButton;
import android.widget.ImageView;
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
import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.ChromeActivity;
import org.chromium.chrome.browser.tab.EmptyTabObserver;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.WindowAndroid;

import java.util.Locale;
import java.util.WeakHashMap;

/** Native controls over the existing Chromium video surface, without a second decoder. */
public final class UpgridPlayer implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "UpgridPlayer";
    // Runtime-only menu ID, outside Android's generated resource ID space.
    public static final int MENU_ID = 0x00f04701;
    private static final WeakHashMap<Activity, UpgridPlayer> ACTIVE = new WeakHashMap<>();
    private static final int PLAY = 0;
    private static final int PAUSE = 1;
    private static final int SEEK = 2;
    private static final int RATE = 3;
    private static final int MUTE = 4;
    private static final int LOOP = 5;
    private static final int FIT = 6;
    private static final int CAPTIONS = 9;
    private static final int DOWNLOAD = 10;
    private static final int CAST = 11;
    private static final int PIP = 12;
    private final Activity mActivity;
    private final Tab mTab;
    private final WebContents mContents;
    private final boolean mAutomatic;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final long mStarted = SystemClock.uptimeMillis();
    private final WindowAndroid mWindow;
    private UpgridOrientationLock mOrientationLock;
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
    private LinearLayout mTop;
    private LinearLayout mBottom;
    private PlayerSurface mRoot;
    private ImageButton mPlay;
    private ImageButton mLoop;
    private ImageButton mMirror;
    private boolean mMirrorHorizontal;
    private boolean mMirrorVertical;
    private Button mUnlock;
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
    private boolean mClosed;
    private boolean mControls = true;
    private boolean mHasFullscreen;
    private Callback<Boolean> mAttachResult;
    private boolean mLocked;
    private int mFitMode;
    private long mLastResponse;
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
        if (tab == null || tab.getWebContents() == null) return;
        UpgridPlayer previous = ACTIVE.get(activity);
        if (previous != null) previous.close(false);
        UpgridPlayer player = new UpgridPlayer(activity, tab, false);
        ACTIVE.put(activity, player);
        player.start();
    }

    public static boolean isActive(Activity activity) {
        return ACTIVE.containsKey(activity);
    }

    public static void adoptFullscreen(Activity activity, Tab tab, Callback<Boolean> result) {
        if (isActive(activity) || activity.isFinishing() || activity.isDestroyed()
                || tab == null || tab.getWebContents() == null) {
            result.onResult(false);
            return;
        }
        UpgridPlayer player = new UpgridPlayer(activity, tab, true);
        player.mAttachResult = result;
        ACTIVE.put(activity, player);
        player.start();
    }

    public static void closeForActivity(Activity activity) {
        UpgridPlayer player = ACTIVE.get(activity);
        if (player != null) player.close(true);
    }

    public static void onFullscreenExited(Activity activity, Tab tab) {
        UpgridPlayer player = ACTIVE.get(activity);
        if (player != null && player.mTab == tab) {
            // The browser/site already exited. Release immediately so a new
            // fullscreen request does not hit an old ACTIVE session, and do
            // not send a second exit back into FullscreenManager.
            // A tab-hide observer can trigger this exit before our own
            // onHidden callback runs. Preserve background/tab-switch pause.
            player.close(tab.isHidden(), false);
        }
    }

    private UpgridPlayer(Activity activity, Tab tab, boolean automatic) {
        mActivity = activity;
        mTab = tab;
        mContents = tab.getWebContents();
        mAutomatic = automatic;
        mWindow = mContents.getTopLevelNativeWindow();
    }

    private void start() {
        Log.i(TAG, "Starting controls; automatic=%b", mAutomatic);
        mLastResponse = mStarted;
        mTab.addObserver(mObserver);
        mActivity.getApplication().registerActivityLifecycleCallbacks(this);
        mOrientationLock = UpgridOrientationLock.acquire(mActivity, mWindow);
        mHandler.postDelayed(mWatchdog, 1000);
        UpgridPlayerJni.get().open(mContents, mAutomatic, this::onPoll);
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
                // Native error codes contain neither website URLs nor credentials.
                Log.w(TAG, "Control channel failed: %s", state.optString("error", "invalid_state"));
                fail("Видео недоступно. Вернитесь к странице и выберите видео.");
                return false;
            }
            if (state.optBoolean("pipRequested")) {
                close(false, false);
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
            reportAttachment(true);
            return true;
        } catch (JSONException e) {
            fail("Не удалось открыть плеер");
            return false;
        }
    }

    private void command(int command, double value) {
        if (mClosed || mContents.isDestroyed()) return;
        Log.i(TAG, "Control command=%d", command);
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
        Log.i(TAG, "Control overlay opened");
        mDialog = new Dialog(mActivity, R.style.Theme_Chromium_Activity_Fullscreen_Transparent);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mDialog.setOnCancelListener(dialog -> close(false));
        PlayerSurface root = new PlayerSurface(mActivity);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setContentDescription("Видеоплеер Upgrid");
        mRoot = root;
        mTop = row();
        mTop.setPadding(dp(16), dp(20), dp(16), dp(28));
        mTop.setBackgroundResource(org.chromium.chrome.browser.toolbar.R.drawable.upgrid_bg_fs_top_gradient);
        mTop.addView(icon(org.chromium.chrome.browser.toolbar.R.drawable.upgrid_ic_arrow_back,
                "Назад", () -> close(false), true, 48));
        mLoop = icon(org.chromium.chrome.browser.toolbar.R.drawable.upgrid_ic_repeat,
                "Повтор", () -> command(LOOP, mState.optBoolean("loop") ? 0 : 1), true, 48);
        mTop.addView(mLoop);
        mMirror = icon(org.chromium.chrome.browser.toolbar.R.drawable.upgrid_ic_player_mirror,
                "Отразить видео", this::showMirrorOptions, true, 48);
        mTop.addView(mMirror);
        mTop.addView(icon(org.chromium.chrome.browser.toolbar.R.drawable.upgrid_ic_player_scale,
                "Масштаб", this::showScaleOptions, true, 48));
        mTop.addView(icon(org.chromium.chrome.browser.toolbar.R.drawable.upgrid_ic_rotate_phone,
                "Поворот", () -> mActivity.setRequestedOrientation(
                        root.getWidth() > root.getHeight()
                                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE), true, 48));
        mTop.addView(icon(org.chromium.chrome.browser.toolbar.R.drawable.upgrid_ic_more_vert,
                "Ещё", this::showMoreOptions, true, 48));
        root.addView(mTop, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        mUnlock = button("Разблокировать", () -> { mLocked = false; showControls(); });
        mUnlock.setVisibility(View.GONE);
        root.addView(mUnlock, new FrameLayout.LayoutParams(dp(156), dp(56), Gravity.TOP | Gravity.END));

        mBottom = new LinearLayout(mActivity);
        mBottom.setOrientation(LinearLayout.VERTICAL);
        mBottom.setPadding(dp(20), dp(20), dp(20), dp(24));
        mBottom.setBackgroundResource(org.chromium.chrome.browser.toolbar.R.drawable.upgrid_bg_fs_bottom_gradient);
        LinearLayout progress = row();
        progress.setBackgroundColor(Color.TRANSPARENT);
        mSeek = new ScrubSeekBar(mActivity);
        mSeek.setMax(10000);
        mSeek.setContentDescription("Позиция видео");
        mSeek.setProgressTintList(ColorStateList.valueOf(0xffffc536));
        mSeek.setThumbTintList(ColorStateList.valueOf(0xffffc536));
        mSeek.setProgressBackgroundTintList(ColorStateList.valueOf(0xff4d4d4d));
        mSeek.setSplitTrack(false);
        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar bar) { beginScrub(); }
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user && mScrub.isActive()) previewScrub(mScrub.duration() * value / 10000.0);
            }
            @Override
            public void onStopTrackingTouch(SeekBar bar) { finishScrub(true); }
        });
        progress.addView(mSeek, new LinearLayout.LayoutParams(0, dp(36), 1));
        mTime = new TextView(mActivity);
        mTime.setTextColor(Color.WHITE);
        mTime.setTextSize(13);
        mTime.setFontFeatureSettings("tnum");
        mTime.setIncludeFontPadding(false);
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(-2, -2);
        timeParams.setMarginStart(dp(12));
        progress.addView(mTime, timeParams);
        mBottom.addView(progress);
        LinearLayout actions = row();
        actions.setBackgroundColor(Color.TRANSPARENT);
        actions.addView(icon(org.chromium.chrome.browser.toolbar.R.drawable.upgrid_ic_volume,
                "Громкость", () -> {
                    AudioManager audio = (AudioManager) mActivity.getSystemService(Context.AUDIO_SERVICE);
                    if (audio != null) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
                }, false, 48));
        spacer(actions);
        mPlay = icon(org.chromium.chrome.browser.toolbar.R.drawable.upgrid_ic_pause,
                "Пауза", () -> command(mState.optBoolean("paused") ? PLAY : PAUSE, 0), false, 60);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(60), dp(60));
        playParams.setMargins(dp(20), 0, dp(20), 0);
        actions.addView(mPlay, playParams);
        spacer(actions);
        actions.addView(icon(org.chromium.chrome.browser.toolbar.R.drawable.upgrid_ic_fullscreen_exit,
                "Вернуться на страницу", () -> close(false), false, 48));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = dp(10);
        mBottom.addView(actions, actionParams);
        root.addView(mBottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> positionControls());
        mBottom.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> positionControls());
        mBuffering = new ProgressBar(mActivity);
        mBuffering.setContentDescription("Буферизация видео");
        mBuffering.setVisibility(View.GONE);
        root.addView(mBuffering, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        mGestureStatus = new TextView(mActivity);
        mGestureStatus.setTextColor(Color.WHITE);
        mGestureStatus.setTextSize(16);
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
                                return root.performClick();
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
        root.setGestures(gestures);
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

    /** Keeps screen-reader clicks and confirmed single taps on the same control path. */
    private final class PlayerSurface extends FrameLayout {
        private GestureDetector mGestures;

        PlayerSurface(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
        }

        void setGestures(GestureDetector gestures) {
            mGestures = gestures;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            if (!mClosed && !mLocked) setControls(!mControls);
            return true;
        }

        // Lint cannot follow GestureDetector's delayed onSingleTapConfirmed callback,
        // which calls performClick above. Calling it on ACTION_UP would also fire for
        // the first tap of a double-tap seek and incorrectly toggle the controls.
        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (mGestures == null) return super.onTouchEvent(event);
            // The detector calls performClick only after a single tap is confirmed,
            // so a double-tap seek does not also toggle controls.
            boolean handled = mGestures.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (mGestureAxis == 3) {
                    finishScrub(event.getActionMasked() == MotionEvent.ACTION_UP);
                    handled = true;
                }
                mGestureAxis = 0;
            }
            return handled;
        }
    }

    /** Observes cancellation while retaining the standard SeekBar input and accessibility. */
    private final class ScrubSeekBar extends SeekBar {
        ScrubSeekBar(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) finishScrub(false);
            return super.dispatchTouchEvent(event);
        }
    }

    private ImageButton icon(int drawable, String label, Runnable action, boolean weighted, int size) {
        ImageButton button = new ImageButton(mActivity);
        button.setImageResource(drawable);
        button.setColorFilter(Color.WHITE);
        button.setContentDescription(label);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setMinimumWidth(0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40ffffff),
                new ColorDrawable(Color.TRANSPARENT), new ColorDrawable(Color.WHITE)));
        button.setLayoutParams(weighted
                ? new LinearLayout.LayoutParams(0, dp(size), 1)
                : new LinearLayout.LayoutParams(dp(size), dp(size)));
        button.setOnClickListener(view -> { showControls(); action.run(); });
        return button;
    }

    private void spacer(LinearLayout row) {
        // A wrap-content plain View would consume the full available height.
        row.addView(new View(mActivity), new LinearLayout.LayoutParams(0, dp(1), 1));
    }

    private void positionControls() {
        if (mRoot == null || mState == null || mBottom.getHeight() <= 0) return;
        int width = mRoot.getWidth();
        int height = mRoot.getHeight();
        int bottom = Math.max(0, height - mBottom.getHeight());
        int top = bottom;
        double videoWidth = mState.optDouble("videoWidth");
        double videoHeight = mState.optDouble("videoHeight");
        if (mFitMode == 0 && height > width && width > 0
                && Double.isFinite(videoWidth) && Double.isFinite(videoHeight)
                && videoWidth > 0 && videoHeight > 0) {
            double frameHeight = Math.min(height, width / videoWidth * videoHeight);
            top = Math.max(0, Math.min(bottom, (int) Math.round((height + frameHeight) / 2)));
        }
        mBottom.setTranslationY(top - mBottom.getTop());
    }

    private void showScaleOptions() {
        showOptions(new AlertDialog.Builder(mActivity).setTitle("Масштаб")
                .setSingleChoiceItems(new String[] {"Вписать", "Заполнить с обрезкой", "Растянуть"},
                        mFitMode, (dialog, index) -> {
                            mFitMode = index;
                            command(FIT + index, 0);
                            dialog.dismiss();
                        }));
    }

    private void showMirrorOptions() {
        showOptions(new AlertDialog.Builder(mActivity).setTitle("Отразить видео")
                .setMultiChoiceItems(new String[] {"По горизонтали", "По вертикали"},
                        new boolean[] {mMirrorHorizontal, mMirrorVertical}, (dialog, index, checked) -> {
                            boolean horizontal = index == 0 ? checked : mMirrorHorizontal;
                            boolean vertical = index == 1 ? checked : mMirrorVertical;
                            mMirrorHorizontal = horizontal;
                            mMirrorVertical = vertical;
                            String option = horizontal && vertical ? "both"
                                    : horizontal ? "horizontal" : vertical ? "vertical" : "off";
                            UpgridPlayerJni.get().siteOptions(mContents, "mirror", option, result -> {
                                if (mClosed) return;
                                try {
                                    if (new JSONObject(result).optBoolean("ok")) {
                                        mMirror.setColorFilter(mMirrorHorizontal || mMirrorVertical ? 0xffffc536 : Color.WHITE);
                                        return;
                                    }
                                } catch (JSONException ignored) {}
                                Toast.makeText(mActivity, "Не удалось отразить это видео", Toast.LENGTH_SHORT).show();
                            });
                        }).setPositiveButton(android.R.string.ok, null));
    }

    private void showMoreOptions() {
        String[] labels = {"Скорость", "Субтитры", "Качество YouTube", "Скачать",
                "Картинка в картинке", "Трансляция", "Включить / выключить звук", "Блокировка"};
        showOptions(new AlertDialog.Builder(mActivity).setItems(labels, (dialog, index) -> {
            switch (index) {
                case 0:
                    showOptions(new AlertDialog.Builder(mActivity).setTitle("Скорость")
                            .setItems(new String[] {"0.25×", "0.5×", "0.75×", "1×", "1.25×", "1.5×", "1.75×", "2×"},
                                    (picker, speed) -> command(RATE, (speed + 1) * 0.25)));
                    break;
                case 1: showCaptions(); break;
                case 2: showSiteOptions(false); break;
                case 3:
                    if (!mState.optBoolean("canDownload")) {
                        Toast.makeText(mActivity, "Этот поток нельзя скачать как видеофайл", Toast.LENGTH_SHORT).show();
                    } else {
                        UpgridPlayerJni.get().control(mContents, DOWNLOAD, 0,
                                value -> { if (receive(value)) close(false); });
                    }
                    break;
                case 4: case 5:
                    if (!mState.optBoolean(index == 4 ? "canPip" : "canCast")) {
                        Toast.makeText(mActivity, "Недоступно для этого видео или устройства", Toast.LENGTH_SHORT).show();
                    } else {
                        UpgridPlayerJni.get().control(mContents, index == 4 ? PIP : CAST, 0,
                                value -> { if (receive(value) && index == 5) close(false); });
                    }
                    break;
                case 6: command(MUTE, mState.optBoolean("muted") ? 0 : 1); break;
                case 7: mLocked = true; setControls(false); break;
                default: break;
            }
        }));
    }

    private void showCaptions() {
        JSONArray tracks = mState.optJSONArray("tracks");
        int count = tracks == null ? 0 : tracks.length();
        if (count == 0) { showSiteOptions(true); return; }
        String[] labels = new String[count + 1];
        labels[0] = "Выключить";
        for (int i = 0; i < count; i++) labels[i + 1] = tracks.optJSONObject(i).optString("label");
        showOptions(new AlertDialog.Builder(mActivity).setTitle("Субтитры")
                .setItems(labels, (dialog, index) -> command(CAPTIONS,
                        index == 0 ? 0 : tracks.optJSONObject(index - 1).optInt("id"))));
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
        mTime.setText(
                mActivity.getString(
                        R.string.upgrid_player_time_range,
                        time(mScrub.position()),
                        time(mScrub.duration())));
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
        double duration = mState.optDouble("duration");
        double position = mState.optDouble("position");
        boolean paused = mState.optBoolean("paused") || mState.optBoolean("ended");
        mPlay.setImageResource(paused
                ? org.chromium.chrome.browser.toolbar.R.drawable.upgrid_ic_play_filled
                : org.chromium.chrome.browser.toolbar.R.drawable.upgrid_ic_pause);
        mPlay.setContentDescription(paused ? "Играть" : "Пауза");
        mLoop.setColorFilter(mState.optBoolean("loop") ? 0xffffc536 : Color.WHITE);
        positionControls();
        if (!mScrub.isActive())
            mTime.setText(
                    duration > 0
                            ? mActivity.getString(
                                    R.string.upgrid_player_time_range, time(position), time(duration))
                            : mActivity.getString(R.string.upgrid_player_live_time, time(position)));
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
    }

    private void fail(String message) {
        if (!mClosed && (!mAutomatic || mHasFullscreen))
            Toast.makeText(mActivity, message, Toast.LENGTH_LONG).show();
        close(false);
    }

    private void close(boolean pause) {
        close(pause, true);
    }

    private void reportAttachment(boolean attached) {
        Callback<Boolean> result = mAttachResult;
        mAttachResult = null;
        if (result != null) result.onResult(attached);
    }

    private void close(boolean pause, boolean exitFullscreen) {
        if (mClosed) return;
        Log.i(TAG, "Closing controls; pause=%b", pause);
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
        if (mOrientationLock != null) mOrientationLock.close();
        if (ACTIVE.get(mActivity) == this) ACTIVE.remove(mActivity);
        // Renderer release can fail after navigation, source replacement, or a
        // lost Mojo channel. Browser chrome must still return immediately.
        // Let Chromium's manager restore controls and notify WebContents, as
        // it does for the system Back button. PiP owns its separate handoff.
        if (exitFullscreen && (mHasFullscreen || !mAutomatic)
                && mActivity instanceof ChromeActivity chromeActivity) {
            chromeActivity.getFullscreenManager().exitPersistentFullscreenMode();
        }
        reportAttachment(false);
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
                @JniType("content::WebContents*") WebContents contents,
                boolean fullscreenOnly,
                Callback<String> callback);

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
