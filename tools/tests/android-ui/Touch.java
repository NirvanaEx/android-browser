package com.upgrid.uitest;

import android.app.UiAutomation;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

/** Emulator test input with a measured double-tap interval. Never bundled in Upgrid. */
public final class Touch {
    public static void main(String[] args) throws Exception {
        int x = Integer.parseInt(args[0]), y = Integer.parseInt(args[1]);
        int count = Integer.parseInt(args[2]);
        if (count < 1 || count > 2) throw new IllegalArgumentException("One or two taps only");
        HandlerThread thread = new HandlerThread("upgrid-test-touch"); thread.start();
        Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
        UiAutomation ui = (UiAutomation) UiAutomation.class.getConstructor(Looper.class,
            Class.forName("android.app.IUiAutomationConnection")).newInstance(thread.getLooper(), connection);
        try {
            UiAutomation.class.getMethod("connect").invoke(ui);
            for (int tap = 0; tap < count; tap++) {
                long down = SystemClock.uptimeMillis();
                for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                    MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
                    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                    if (!ui.injectInputEvent(event, true)) throw new AssertionError("Input injection failed");
                    event.recycle(); SystemClock.sleep(35);
                }
                SystemClock.sleep(60);
            }
            SystemClock.sleep(500);
            System.out.println("TOUCH_OK");
        } finally {
            UiAutomation.class.getMethod("disconnect").invoke(ui); thread.quitSafely();
        }
    }
}
