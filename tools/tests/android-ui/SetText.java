package com.upgrid.uitest;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

/** Shell-only test input helper; never packaged in the browser. */
public final class SetText {
    private static final long WAIT_MILLISECONDS = 5000;
    private static final int MAX_NODES = 5000;

    public static void main(String[] args) {
        try {
            int length = setText(args);
            System.out.println("SET_TEXT_OK length=" + length);
        } catch (Throwable failure) {
            Throwable reason = failure;
            for (int depth = 0; depth < 8 && reason instanceof java.lang.reflect.InvocationTargetException &&
                    reason.getCause() != null; depth++) reason = reason.getCause();
            String message = reason.getMessage();
            message = message == null ? "operation failed" : message.replace('\r', ' ').replace('\n', ' ');
            if (message.length() > 200) message = message.substring(0, 200);
            System.out.println("SET_TEXT_FAILED: " + reason.getClass().getSimpleName() + ": " + message);
            // setText's finally has completed before reporting a normal test
            // failure. Do not turn a missing field into an Android crash report.
            System.exit(1);
        }
    }

    private static int setText(String[] args) throws Exception {
        if (args.length != 2 || args[0].isEmpty()) {
            throw new IllegalArgumentException("Usage: SetText <exact-resource-id> <value>");
        }
        String resourceId = args[0], value = args[1];
        HandlerThread thread = null;
        UiAutomation ui = null;
        AccessibilityNodeInfo target = null;
        boolean connected = false;
        try {
            thread = new HandlerThread("upgrid-ui-set-text");
            thread.start();
            Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            ui = (UiAutomation) UiAutomation.class.getConstructor(Looper.class,
                Class.forName("android.app.IUiAutomationConnection")).newInstance(thread.getLooper(), connection);
            UiAutomation.class.getMethod("connect").invoke(ui);
            connected = true;
            AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            ui.setServiceInfo(info);

            long deadline = SystemClock.uptimeMillis() + WAIT_MILLISECONDS;
            do {
                target = findTarget(ui, resourceId, deadline);
                if (target != null) break;
                SystemClock.sleep(100);
            } while (SystemClock.uptimeMillis() < deadline);
            if (target == null) throw new AssertionError("No focused editable field with the exact resource ID");

            // One accessibility edit avoids synthetic key-event loss on slow
            // emulators. Do not move focus or submit the field as a side effect.
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                throw new AssertionError("Focused field rejected ACTION_SET_TEXT");
            }

            deadline = SystemClock.uptimeMillis() + WAIT_MILLISECONDS;
            do {
                if (target != null && target.refresh() && resourceId.equals(target.getViewIdResourceName()) &&
                        target.isFocused() && target.isEditable()) {
                    CharSequence actual = target.getText();
                    if (value.contentEquals(actual == null ? "" : actual)) {
                        return value.length();
                    }
                }
                // Compose may replace a semantics node after an edit. Re-read
                // the same focused field, without repeating ACTION_SET_TEXT.
                if (target != null) target.recycle();
                target = null;
                SystemClock.sleep(100);
                target = findTarget(ui, resourceId, deadline);
            } while (SystemClock.uptimeMillis() < deadline);
            throw new AssertionError("Refreshed field did not retain the requested text");
        } finally {
            try {
                if (target != null) target.recycle();
            } finally {
                try {
                    if (connected) UiAutomation.class.getMethod("disconnect").invoke(ui);
                } finally {
                    if (thread != null) {
                        try {
                            thread.quitSafely();
                        } finally {
                            thread.join(1000);
                        }
                    }
                }
            }
        }
    }

    private static AccessibilityNodeInfo findTarget(UiAutomation ui, String resourceId, long deadline) {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        if (root == null) return null;
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null) {
                if (matchesTarget(focused, resourceId)) return focused;
                focused.recycle();
            }
            return findFocusedEditable(root, resourceId, 0, new int[]{MAX_NODES}, deadline);
        } finally {
            root.recycle();
        }
    }

    private static AccessibilityNodeInfo findFocusedEditable(
            AccessibilityNodeInfo node, String resourceId, int depth, int[] remaining, long deadline) {
        if (SystemClock.uptimeMillis() >= deadline) return null;
        if (depth > 100 || remaining[0]-- <= 0) {
            throw new AssertionError("Accessibility search exceeded its node/depth limit");
        }
        if (matchesTarget(node, resourceId)) {
            // The caller owns this copy; every traversed original is recycled.
            return AccessibilityNodeInfo.obtain(node);
        }
        for (int index = 0; index < node.getChildCount() && SystemClock.uptimeMillis() < deadline; index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo found = findFocusedEditable(child, resourceId, depth + 1, remaining, deadline);
                if (found != null) return found;
            } finally {
                child.recycle();
            }
        }
        return null;
    }

    private static boolean matchesTarget(AccessibilityNodeInfo node, String resourceId) {
        return resourceId.equals(node.getViewIdResourceName()) && node.isFocused() && node.isEditable() &&
            node.isEnabled() && node.isVisibleToUser();
    }
}
