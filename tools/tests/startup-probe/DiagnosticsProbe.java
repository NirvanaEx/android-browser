package com.upgrid.startupprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.os.Process;
import android.os.Looper;
import android.os.StrictMode;
import android.os.SystemClock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

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
            if (mode.equals("ui_io")) {
                ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
                Object[] instance = new Object[1];
                try {
                    runOnMainSync(() -> {
                        StrictMode.ThreadPolicy previous = StrictMode.getThreadPolicy();
                        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                            .detectDiskReads().detectDiskWrites().penaltyDeath().build());
                        try {
                            instance[0] = type.getDeclaredConstructor(Context.class, boolean.class, ScheduledExecutorService.class)
                                .newInstance(context, true, io);
                            for (int index = 0; index < 100; index++) {
                                type.getMethod("breadcrumb", String.class).invoke(instance[0], "ui_probe_" + index);
                            }
                            Throwable failure = new IllegalStateException("DO_NOT_UPLOAD_UI_PROBE") {
                                @Override public StackTraceElement[] getStackTrace() {
                                    if (Looper.myLooper() == Looper.getMainLooper()) {
                                        throw new AssertionError("Diagnostic stack traversed on UI thread");
                                    }
                                    return super.getStackTrace();
                                }
                            };
                            type.getMethod("error", String.class, Throwable.class).invoke(instance[0], "ui_io_probe", failure);
                        } catch (Exception exception) { throw new AssertionError(exception); }
                        finally { StrictMode.setThreadPolicy(previous); }
                    });
                    SystemClock.sleep(1000);
                    type.getMethod("flush").invoke(instance[0]);
                } finally { io.shutdown(); }
            } else if (mode.equals("browser_empty")) {
                probeEmptyBrowser(context, activity);
            } else if (mode.equals("tab_memory")) {
                // Watch the real debug store while the test driver opens fixture tabs.
                // This probe is separate from the distributed APK; never print URLs/forms.
                for (int sample = 0; sample < 72; sample++) {
                    final String[] snapshot = new String[1];
                    runOnMainSync(() -> {
                        try {
                            Object app = context.getApplicationContext();
                            Object components = app.getClass().getMethod("getComponents").invoke(app);
                            Object core = components.getClass().getMethod("getCore").invoke(components);
                            Object store = core.getClass().getMethod("getStore").invoke(core);
                            Object state = store.getClass().getMethod("getState").invoke(store);
                            java.util.List<?> tabs = (java.util.List<?>) state.getClass().getMethod("getTabs").invoke(state);
                            int loaded = 0, forms = 0, formLoaded = 0, media = 0, restorable = 0, idle = 0;
                            for (Object tab : tabs) {
                                Object engineState = tab.getClass().getMethod("getEngineState").invoke(tab);
                                boolean linked = engineState.getClass().getMethod("getEngineSession").invoke(engineState) != null;
                                if (linked) loaded++;
                                if (linked && engineState.getClass().getMethod("getEngineSessionState").invoke(engineState) != null) restorable++;
                                long accessed = (Long) tab.getClass().getMethod("getLastAccess").invoke(tab);
                                long visible = (Long) tab.getClass().getMethod("getLastVisibleAt").invoke(tab);
                                if (linked && System.currentTimeMillis() - Math.max(accessed, visible) > 60000) idle++;
                                Object content = tab.getClass().getMethod("getContent").invoke(tab);
                                if (Boolean.TRUE.equals(content.getClass().getMethod("getHasFormData").invoke(content))) {
                                    forms++;
                                    if (linked) formLoaded++;
                                }
                                if (tab.getClass().getMethod("getMediaSessionState").invoke(tab) != null) media++;
                            }
                            snapshot[0] = "tabs=" + tabs.size() + " loaded=" + loaded + " forms=" + forms +
                                " formsLoaded=" + formLoaded + " media=" + media + " restorable=" + restorable + " idle=" + idle + "\n";
                        } catch (Exception failure) { throw new AssertionError(failure); }
                    });
                    Bundle status = new Bundle();
                    status.putString("stream", snapshot[0]);
                    sendStatus(0, status);
                    SystemClock.sleep(5000);
                }
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

    /** Closes disposable emulator tabs through the normal use case; never clears the profile. */
    private void probeEmptyBrowser(Context context, Activity activity) throws Exception {
        if (!(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))) {
            throw new AssertionError("browser_empty closes tabs and is restricted to a disposable Android emulator");
        }
        final Object[] store = new Object[1];
        final Object[] components = new Object[1];
        runOnMainSync(() -> {
            try {
                Object app = context.getApplicationContext();
                components[0] = app.getClass().getMethod("getComponents").invoke(app);
                Object core = components[0].getClass().getMethod("getCore").invoke(components[0]);
                store[0] = core.getClass().getMethod("getStore").invoke(core);
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        // Do not mistake a delayed session restore for a stable empty browser.
        final boolean[] restored = new boolean[1];
        for (int attempt = 0; attempt < 60 && !restored[0]; attempt++) {
            runOnMainSync(() -> {
                try {
                    Object state = store[0].getClass().getMethod("getState").invoke(store[0]);
                    restored[0] = Boolean.TRUE.equals(state.getClass().getMethod("getRestoreComplete").invoke(state));
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            if (!restored[0]) SystemClock.sleep(500);
        }
        if (!restored[0]) throw new AssertionError("Session restore did not complete before browser_empty");

        final Object[] homepageBinding = new Object[1];
        final Object[] controller = new Object[1];
        final Object[] childManager = new Object[1];
        final Object[] attachListener = new Object[1];
        final Object[] browserLifecycle = new Object[1];
        final Object[] browserObserver = new Object[1];
        final boolean[] emptyBrowserViewStarted = new boolean[1];
        final String[] finalScreen = new String[1];
        final ClassLoader loader = context.getClassLoader();
        final Class<?> attachType = loader.loadClass("androidx.fragment.app.FragmentOnAttachListener");
        final Class<?> lifecycleObserverType = loader.loadClass("androidx.lifecycle.LifecycleObserver");
        final Class<?> lifecycleEventType = loader.loadClass("androidx.lifecycle.LifecycleEventObserver");
        final int destination = context.getResources().getIdentifier("browserFragment", "id", context.getPackageName());
        final int homeDestination = context.getResources().getIdentifier("homeFragment", "id", context.getPackageName());
        if (destination == 0) throw new AssertionError("Browser destination missing");
        try {
            runOnMainSync(() -> {
                try {
                    // Pinned HomeActivity's private lazy getter returns the existing binding.
                    // Stop its coroutine before RemoveAllTabs: otherwise it immediately adds about:home.
                    Class<?> homeActivity = context.getClassLoader().loadClass("org.mozilla.fenix.HomeActivity");
                    java.lang.reflect.Method getter = homeActivity.getDeclaredMethod("getHomepageTabBinding");
                    getter.setAccessible(true);
                    homepageBinding[0] = getter.invoke(activity);
                    homepageBinding[0].getClass().getMethod("stop").invoke(homepageBinding[0]);
                    Object useCases = components[0].getClass().getMethod("getUseCases").invoke(components[0]);
                    Object tabs = useCases.getClass().getMethod("getTabsUseCases").invoke(useCases);
                    Object removeAll = tabs.getClass().getMethod("getRemoveAllTabs").invoke(tabs);
                    removeAll.getClass().getMethod("invoke", boolean.class).invoke(removeAll, false);
                    assertEmptyStore(store[0]);

                    Object manager = activity.getClass().getMethod("getSupportFragmentManager").invoke(activity);
                    java.util.List<?> fragments = (java.util.List<?>) manager.getClass().getMethod("getFragments").invoke(manager);
                    Object host = fragments.stream().filter(value -> value.getClass().getSimpleName().contains("NavHostFragment")).findFirst().get();
                    controller[0] = host.getClass().getMethod("getNavController").invoke(host);
                    childManager[0] = host.getClass().getMethod("getChildFragmentManager").invoke(host);
                    // observeRestoreComplete legitimately redirects an empty BrowserFragment home.
                    // Capture its view at ON_START, after onViewCreated/initializeUI has returned,
                    // rather than requiring that transient destination to remain for five seconds.
                    attachListener[0] = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{attachType},
                        (proxy, method, args) -> {
                            if (!method.getName().equals("onAttachFragment")) return proxyObjectMethod(proxy, method, args);
                            Object browser = args[1];
                            if (!browser.getClass().getName().equals("org.mozilla.fenix.browser.BrowserFragment")) return null;
                            assertEmptyStore(store[0]);
                            browserLifecycle[0] = browser.getClass().getMethod("getLifecycle").invoke(browser);
                            browserObserver[0] = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{lifecycleEventType},
                                (observer, callback, values) -> {
                                    if (!callback.getName().equals("onStateChanged")) return proxyObjectMethod(observer, callback, values);
                                    if (!values[1].toString().equals("ON_START")) return null;
                                    assertEmptyStore(store[0]);
                                    Object view = browser.getClass().getMethod("getView").invoke(browser);
                                    if (view == null) throw new AssertionError("Empty BrowserFragment reached START without its view");
                                    Class<?> baseBrowser = loader.loadClass("org.mozilla.fenix.browser.BaseBrowserFragment");
                                    java.lang.reflect.Field initialized = baseBrowser.getDeclaredField("browserInitialized");
                                    initialized.setAccessible(true);
                                    if (initialized.getBoolean(browser)) throw new AssertionError("Expected tab-null initializeUI path");
                                    emptyBrowserViewStarted[0] = true;
                                    return null;
                                });
                            browserLifecycle[0].getClass().getMethod("addObserver", lifecycleObserverType)
                                .invoke(browserLifecycle[0], browserObserver[0]);
                            return null;
                        });
                    childManager[0].getClass().getMethod("addFragmentOnAttachListener", attachType)
                        .invoke(childManager[0], attachListener[0]);
                    controller[0].getClass().getMethod("navigate", int.class).invoke(controller[0], destination);
                    childManager[0].getClass().getMethod("executePendingTransactions").invoke(childManager[0]);
                    assertEmptyStore(store[0]);
                    if (!emptyBrowserViewStarted[0]) throw new AssertionError("Did not observe tab-null BrowserFragment view initialization");
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            SystemClock.sleep(5000);
            runOnMainSync(() -> {
                try {
                    assertEmptyStore(store[0]);
                    if (activity.isFinishing() || activity.isDestroyed()) throw new AssertionError("Browser activity stopped");
                    Object current = controller[0].getClass().getMethod("getCurrentDestination").invoke(controller[0]);
                    int currentId = current == null ? 0 : (Integer) current.getClass().getMethod("getId").invoke(current);
                    Object fragment = childManager[0].getClass().getMethod("getPrimaryNavigationFragment").invoke(childManager[0]);
                    String expectedClass;
                    if (currentId == destination) expectedClass = "org.mozilla.fenix.browser.BrowserFragment";
                    else if (currentId == homeDestination) expectedClass = "org.mozilla.fenix.home.HomeFragment";
                    else throw new AssertionError("Unexpected destination after empty browser initialization");
                    if (!emptyBrowserViewStarted[0] || fragment == null || !fragment.getClass().getName().equals(expectedClass) ||
                            !Boolean.TRUE.equals(fragment.getClass().getMethod("isResumed").invoke(fragment))) {
                        throw new AssertionError("Browser did not remain healthy after tab-null initialization");
                    }
                    finalScreen[0] = fragment.getClass().getSimpleName();
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            Bundle status = new Bundle();
            status.putString("stream", "PASS browser_empty: observed BrowserFragment view after tab-null initializeUI; " +
                "tabs=0 selected=null; " + finalScreen[0] + " resumed after 5 seconds\n");
            sendStatus(0, status);
        } finally {
            runOnMainSync(() -> {
                try {
                    if (attachListener[0] != null) childManager[0].getClass()
                        .getMethod("removeFragmentOnAttachListener", attachType).invoke(childManager[0], attachListener[0]);
                    if (browserObserver[0] != null) browserLifecycle[0].getClass()
                        .getMethod("removeObserver", lifecycleObserverType).invoke(browserLifecycle[0], browserObserver[0]);
                    // Restore ordinary runtime behavior without changing preferences.
                    if (homepageBinding[0] != null && !activity.isFinishing() && !activity.isDestroyed()) {
                        homepageBinding[0].getClass().getMethod("start").invoke(homepageBinding[0]);
                    }
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
        }
    }

    private static Object proxyObjectMethod(Object proxy, java.lang.reflect.Method method, Object[] args) {
        if (method.getName().equals("equals")) return proxy == args[0];
        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
        if (method.getName().equals("toString")) return "UpgridEmptyBrowserProbeObserver";
        return null;
    }

    private static void assertEmptyStore(Object store) throws Exception {
        Object state = store.getClass().getMethod("getState").invoke(store);
        java.util.List<?> tabs = (java.util.List<?>) state.getClass().getMethod("getTabs").invoke(state);
        Object selected = state.getClass().getMethod("getSelectedTabId").invoke(state);
        if (!tabs.isEmpty() || selected != null) {
            throw new AssertionError("browser_empty requires tabs=0 selected=null, got tabs=" + tabs.size() +
                " selectedPresent=" + (selected != null));
        }
    }
}
