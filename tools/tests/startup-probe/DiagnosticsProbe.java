package com.upgrid.startupprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

/** Separate test APK: synthetic failures on an emulator, never included in Upgrid. */
public final class DiagnosticsProbe extends Instrumentation {
    private String mode;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        mode = arguments.getString("mode", "caught");
        start();
    }

    @Override public void onStart() {
        try {
            Context context = getTargetContext();
            Class<?> type = context.getClassLoader().loadClass("org.mozilla.fenix.upgrid.UpgridDiagnostics");
            Object diagnostics = type.getMethod("get", Context.class).invoke(null, context);
            Intent intent = new Intent().setClassName(context.getPackageName(), "org.mozilla.fenix.HomeActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity = startActivitySync(intent);
            SystemClock.sleep(4000);
            type.getMethod("breadcrumb", String.class).invoke(diagnostics, "diagnostic_probe_" + mode);
            if (mode.equals("browser_empty")) {
                runOnMainSync(() -> {
                    try {
                        Object manager = activity.getClass().getMethod("getSupportFragmentManager").invoke(activity);
                        java.util.List<?> fragments = (java.util.List<?>) manager.getClass().getMethod("getFragments").invoke(manager);
                        Object host = fragments.stream().filter(value -> value.getClass().getSimpleName().contains("NavHostFragment")).findFirst().get();
                        Object controller = host.getClass().getMethod("getNavController").invoke(host);
                        int destination = context.getResources().getIdentifier("browserFragment", "id", context.getPackageName());
                        if (destination == 0) throw new AssertionError("Browser destination missing");
                        controller.getClass().getMethod("navigate", int.class).invoke(controller, destination);
                        manager.getClass().getMethod("executePendingTransactions").invoke(manager);
                    } catch (Exception failure) { throw new AssertionError(failure); }
                });
                SystemClock.sleep(5000);
            } else if (mode.equals("caught") || mode.equals("offline")) {
                type.getMethod("error", String.class, Throwable.class).invoke(diagnostics, "diagnostic_privacy_probe_" + mode,
                    new IllegalStateException("DO_NOT_UPLOAD_PASSWORD https://example.invalid/private?token=DO_NOT_UPLOAD_TOKEN"));
                SystemClock.sleep(3000);
                type.getMethod("flush").invoke(diagnostics);
            } else if (mode.equals("fatal")) {
                runOnMainSync(() -> { throw new IllegalStateException("Upgrid controlled crash probe"); });
                throw new AssertionError("Fatal probe did not stop process");
            } else if (mode.equals("anr")) {
                runOnMainSync(() -> SystemClock.sleep(12000));
                SystemClock.sleep(3000);
                type.getMethod("flush").invoke(diagnostics);
            } else if (mode.equals("native")) {
                Process.sendSignal(Process.myPid(), 11);
                SystemClock.sleep(5000);
                throw new AssertionError("Native probe did not stop process");
            } else {
                throw new IllegalArgumentException("Unknown test mode");
            }
            Bundle result = new Bundle();
            result.putString("stream", "Diagnostics probe completed: " + mode + ". Verify delivery on the VPS.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
