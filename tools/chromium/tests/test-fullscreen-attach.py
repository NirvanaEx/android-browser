#!/usr/bin/env python3
"""Exercise the real coordinator against asynchronous API stubs, on GitHub only."""
import argparse
import os
import pathlib
import subprocess
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
STUBS = {
    'android/app/Activity.java': '''package android.app;
public class Activity {
    public boolean destroyed;
    public boolean isDestroyed() { return destroyed; }
    public boolean isFinishing() { return false; }
    public android.view.View findViewById(int id) { return null; }
}''',
    'android/view/View.java': '''package android.view;
public class View {
    public interface Listener { void click(View view); }
    public void setOnClickListener(Listener listener) {}
}''',
    'android/os/Looper.java': '''package android.os;
public class Looper { public static Looper getMainLooper() { return new Looper(); } }''',
    'android/os/SystemClock.java': '''package android.os;
public class SystemClock {
    public static long now;
    public static long uptimeMillis() { return now; }
}''',
    'android/os/Handler.java': '''package android.os;
import java.util.*;
public class Handler {
    record Task(long due, Runnable action, Handler owner) {}
    static PriorityQueue<Task> queue = new PriorityQueue<>(Comparator.comparingLong(Task::due));
    public Handler(Looper looper) {}
    public void post(Runnable task) { postDelayed(task, 0); }
    public void postDelayed(Runnable task, long delay) { queue.add(new Task(SystemClock.now + delay, task, this)); }
    public void removeCallbacksAndMessages(Object token) { queue.removeIf(t -> t.owner() == this); }
    public static void reset() { queue.clear(); SystemClock.now = 0; }
    public static void advance(long delta) {
        long end = SystemClock.now + delta;
        int count = 0;
        while (!queue.isEmpty() && queue.peek().due() <= end) {
            if (++count > 1000) throw new AssertionError("unbounded retries");
            Task t = queue.remove(); SystemClock.now = t.due(); t.action().run();
        }
        SystemClock.now = end;
    }
}''',
    'org/chromium/base/Log.java': '''package org.chromium.base;
public class Log { public static void i(String tag, String message) {} }''',
    'org/chromium/base/Callback.java': '''package org.chromium.base;
public interface Callback<T> { void onResult(T result); }''',
    'org/chromium/build/annotations/NullMarked.java': '''package org.chromium.build.annotations;
public @interface NullMarked {}''',
    'org/chromium/build/annotations/Nullable.java': '''package org.chromium.build.annotations;
import java.lang.annotation.*;
@Target({ElementType.TYPE_USE}) public @interface Nullable {}''',
    'org/chromium/chrome/browser/toolbar/R.java': '''package org.chromium.chrome.browser.toolbar;
public class R { public static class id { public static final int upgrid_player_button = 1; } }''',
    'org/chromium/content_public/browser/WebContents.java': '''package org.chromium.content_public.browser;
public class WebContents {
    public boolean fullscreen, destroyed;
    public boolean isDestroyed() { return destroyed; }
    public boolean isFullscreenForCurrentTab() { return fullscreen; }
    public Object getTopLevelNativeWindow() { return null; }
}''',
    'org/chromium/chrome/browser/tab/Tab.java': '''package org.chromium.chrome.browser.tab;
public class Tab {
    public boolean interactable = true, hidden;
    public org.chromium.content_public.browser.WebContents contents = new org.chromium.content_public.browser.WebContents();
    public boolean isUserInteractable() { return interactable; }
    public boolean isHidden() { return hidden; }
    public org.chromium.content_public.browser.WebContents getWebContents() { return contents; }
}''',
    'org/chromium/chrome/browser/fullscreen/FullscreenOptions.java': '''package org.chromium.chrome.browser.fullscreen;
public class FullscreenOptions {}''',
    'org/chromium/chrome/browser/fullscreen/FullscreenManager.java': '''package org.chromium.chrome.browser.fullscreen;
import org.chromium.chrome.browser.tab.Tab;
public class FullscreenManager {
    public interface Observer { void onEnterFullscreen(Tab tab, FullscreenOptions options); void onExitFullscreen(Tab tab); }
    public boolean active;
    public void addObserver(Observer observer) {}
    public void removeObserver(Observer observer) {}
    public boolean getPersistentFullscreenMode() { return active; }
}''',
    'org/chromium/chrome/browser/upgrid/UpgridOrientationLock.java': '''package org.chromium.chrome.browser.upgrid;
public class UpgridOrientationLock {
    public static UpgridOrientationLock acquire(android.app.Activity a, Object w) { return new UpgridOrientationLock(); }
    public void close() {}
}''',
    'org/chromium/chrome/browser/upgrid/UpgridPlayer.java': '''package org.chromium.chrome.browser.upgrid;
import android.app.Activity;
import org.chromium.base.Callback;
import org.chromium.chrome.browser.tab.Tab;
public class UpgridPlayer {
    static int calls, failures, exits;
    static boolean active, hold;
    static Callback<Boolean> pending;
    static void reset() { calls = failures = exits = 0; active = hold = false; pending = null; }
    public static void open(Activity a, Tab t) { active = true; }
    public static boolean isActive(Activity a) { return active; }
    public static void adoptFullscreen(Activity a, Tab t, Callback<Boolean> result) {
        calls++; active = true;
        if (hold) { pending = result; return; }
        active = failures-- <= 0;
        result.onResult(active);
    }
    public static void closeForActivity(Activity a) { active = false; }
    public static void onFullscreenExited(Activity a, Tab t) { exits++; active = false; }
}''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', required=True, type=pathlib.Path)
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise SystemExit('Java checks run only on GitHub Actions; no local compilation.')
    with tempfile.TemporaryDirectory(prefix='upgrid-attach-') as directory:
        work = pathlib.Path(directory)
        sources = []
        for name, content in STUBS.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            sources.append(path)
        subprocess.run([args.jdk / 'bin/javac', '-J-Xmx128m', '--release', '17', '-d', str(work),
                        *map(str, sources), str(HERE.parent / 'overlay/UpgridPlayerCoordinator.java'),
                        str(HERE / 'UpgridFullscreenAttachTest.java')], check=True)
        subprocess.run([args.jdk / 'bin/java', '-Xmx64m', '-cp', str(work),
                        'org.chromium.chrome.browser.upgrid.UpgridFullscreenAttachTest'], check=True)
        # Exercise the actual player's exit policy, separately from the
        # coordinator fake. Observer ordering must not turn hiding into Play.
        source = (HERE.parent / 'overlay/UpgridPlayer.java').read_text()
        start = source.index('    public static void onFullscreenExited(')
        end = source.index('\n    private UpgridPlayer(', start)
        exit_source = '''package org.chromium.chrome.browser.upgrid;
import android.app.Activity;
import org.chromium.chrome.browser.tab.Tab;
import java.util.*;
public class UpgridPlayer {
    static Map<Activity, UpgridPlayer> ACTIVE = new HashMap<>();
    Tab mTab = new Tab();
    boolean paused, requestedExit;
    int closes;
    void close(boolean pause, boolean exit) { paused = pause; requestedExit = exit; closes++; }
    // ACTUAL_EXIT
    public static void main(String[] args) {
        Activity activity = new Activity();
        UpgridPlayer player = new UpgridPlayer();
        ACTIVE.put(activity, player);
        onFullscreenExited(activity, player.mTab);
        if (player.closes != 1 || player.paused || player.requestedExit)
            throw new AssertionError("visible return must preserve playback and not exit twice");
        player.mTab.hidden = true;
        onFullscreenExited(activity, player.mTab);
        if (player.closes != 2 || !player.paused || player.requestedExit)
            throw new AssertionError("hidden tab must pause regardless of observer order");
        onFullscreenExited(activity, new Tab());
        if (player.closes != 2) throw new AssertionError("another tab must not release player");
        System.out.println("Actual exit policy preserves visible playback and hidden-tab pause.");
    }
}'''.replace('    // ACTUAL_EXIT', source[start:end])
        exit_dir = work / 'exit-policy'
        exit_dir.mkdir()
        exit_file = exit_dir / 'UpgridPlayer.java'
        exit_file.write_text(exit_source)
        subprocess.run([args.jdk / 'bin/javac', '-J-Xmx128m', '--release', '17', '-cp', str(work),
                        '-d', str(exit_dir), str(exit_file)], check=True)
        subprocess.run([args.jdk / 'bin/java', '-Xmx64m', '-cp', str(exit_dir) + os.pathsep + str(work),
                        'org.chromium.chrome.browser.upgrid.UpgridPlayer'], check=True)


if __name__ == '__main__':
    main()
