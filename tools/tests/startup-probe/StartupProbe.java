package com.upgrid.startupprobe;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.StrictMode;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test APK only: exercise the real startup policy before any fragment disables it. */
public final class StartupProbe extends Instrumentation {
    private final CountDownLatch initialized = new CountDownLatch(1);
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void callApplicationOnCreate(Application app) {
        super.callApplicationOnCreate(app);
        Log.i("UpgridStartupProbe", "Application initialized; triggering startup disk-read diagnostic");
        new File(app.getFilesDir(), "startup-probe-nonexistent").exists();
        StrictMode.noteSlowCall("Upgrid startup regression probe");
        initialized.countDown();
    }

    @Override
    public void onStart() {
        try {
            if (!initialized.await(60, TimeUnit.SECONDS)) {
                throw new AssertionError("Application initialization timed out");
            }
        } catch (InterruptedException exception) {
            throw new AssertionError(exception);
        }
        SystemClock.sleep(10000);
        Bundle result = new Bundle();
        result.putString("stream", "Startup diagnostics did not terminate the application.\n");
        Log.i("UpgridStartupProbe", "PASS: process survived startup diagnostics");
        finish(Activity.RESULT_OK, result);
    }
}
