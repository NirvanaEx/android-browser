// Copyright 2026 Upgrid contributors. All rights reserved.
package org.chromium.chrome.browser.upgrid;

import org.jni_zero.JniType;
import org.jni_zero.NativeMethods;

import org.chromium.base.Log;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content_public.browser.WebContents;

/** Connects the standard Translate menu item to the user's installed TWP extension. */
@NullMarked
public final class UpgridTranslate {
    private UpgridTranslate() {}

    private static @Nullable WebContents getContents(@Nullable Tab tab) {
        if (tab == null || tab.isNativePage()) return null;
        WebContents contents = tab.getWebContents();
        return contents == null || contents.isDestroyed() ? null : contents;
    }

    public static boolean canTranslate(@Nullable Tab tab) {
        WebContents contents = getContents(tab);
        return contents != null && UpgridTranslateJni.get().isAvailable(contents);
    }

    public static boolean translate(@Nullable Tab tab) {
        WebContents contents = getContents(tab);
        if (contents == null) return false;
        boolean dispatched = UpgridTranslateJni.get().translate(contents);
        if (dispatched) Log.i("UpgridTranslate", "Translate menu command dispatched");
        return dispatched;
    }

    @NativeMethods
    interface Natives {
        boolean isAvailable(@JniType("content::WebContents*") WebContents contents);
        boolean translate(@JniType("content::WebContents*") WebContents contents);
    }
}
