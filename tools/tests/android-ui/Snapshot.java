package com.upgrid.uitest;

import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.FileOutputStream;
import org.xmlpull.v1.XmlSerializer;

/** Shell-only test helper; never packaged in the browser. */
public final class Snapshot {
    public static void main(String[] args) throws Exception {
        HandlerThread thread = new HandlerThread("upgrid-ui-snapshot");
        thread.start();
        Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
        UiAutomation ui = (UiAutomation) UiAutomation.class.getConstructor(Looper.class,
            Class.forName("android.app.IUiAutomationConnection")).newInstance(thread.getLooper(), connection);
        try {
            UiAutomation.class.getMethod("connect").invoke(ui);
            AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            ui.setServiceInfo(info);
            AccessibilityNodeInfo root = null;
            for (int attempt = 0; root == null && attempt < 30; attempt++) {
                SystemClock.sleep(100);
                root = ui.getRootInActiveWindow();
            }
            if (root == null) throw new AssertionError("No active test window");
            try (FileOutputStream output = new FileOutputStream(args[0])) {
                XmlSerializer xml = Xml.newSerializer();
                xml.setOutput(output, "UTF-8");
                xml.startDocument("UTF-8", true);
                xml.startTag(null, "hierarchy");
                write(xml, root, 0);
                xml.endTag(null, "hierarchy");
                xml.endDocument();
            }
            System.out.println("SNAPSHOT_OK");
        } finally {
            UiAutomation.class.getMethod("disconnect").invoke(ui);
            thread.quitSafely();
        }
    }
    private static void write(XmlSerializer xml, AccessibilityNodeInfo node, int depth) throws Exception {
        if (depth > 100) return;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        xml.startTag(null, "node");
        xml.attribute(null, "text", text(node.getText()));
        xml.attribute(null, "content-desc", text(node.getContentDescription()));
        xml.attribute(null, "resource-id", text(node.getViewIdResourceName()));
        xml.attribute(null, "bounds", bounds.toShortString().replace(" ", ""));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { write(xml, child, depth + 1); child.recycle(); }
        }
        xml.endTag(null, "node");
    }
    private static String text(CharSequence value) { return value == null ? "" : value.toString(); }
}
