package dev.vmn.coverlauncher;

import android.util.Log;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Moves the existing native confirmation task, preserving its pending ADB key. */
final class UsbDebugging {
    private static final String TAG = "CoverLauncher";
    static final String CLASS = "com.android.systemui.usb.UsbDebuggingActivity";
    private static final Pattern FOLDED = Pattern.compile("mCommittedState=.*name=['\"]?CLOSED");
    private static final Pattern HEADERS = Pattern.compile(
            "(?m)^Display #(\\d+)\\b[^\\n]*|^  \\* Task\\{[0-9a-fA-F]+ #(\\d+) type=(\\w+)[^\\n]*");
    private static final Pattern HISTORY = Pattern.compile(
            "(?m)^\\s*\\* Hist\\s+#\\d+: ActivityRecord\\{[^\\n]*? u\\d+ ([A-Za-z0-9._]+)/([A-Za-z0-9._$]+) t(\\d+)\\b");
    private static final Pattern TOP_DIALOG = Pattern.compile(
            "topResumedActivity=ActivityRecord\\{[^\\n]*com\\.android\\.systemui/(?:\\.usb\\.UsbDebuggingActivity|"
                    + "com\\.android\\.systemui\\.usb\\.UsbDebuggingActivity) t(\\d+)\\b");

    private UsbDebugging() {}

    static boolean isFolded(String snapshot) {
        return FOLDED.matcher(snapshot).find();
    }

    /** Returns true for a live dialog while folded, including one already on the cover. */
    static boolean route(String snapshot, int coverId) throws IOException {
        if (!isFolded(snapshot)) return false;
        Matcher headers = HEADERS.matcher(snapshot);
        Matcher history = HISTORY.matcher(snapshot);
        int displayId = -1;
        int rootTaskId = -1;
        int start = 0;
        String taskType = null;
        while (headers.find()) {
            if (rootTaskId >= 0 && routeTask(history, start, headers.start(), displayId,
                    rootTaskId, taskType, coverId, snapshot)) return true;
            if (headers.group(1) != null) {
                displayId = Integer.parseInt(headers.group(1));
                rootTaskId = -1;
            } else {
                rootTaskId = Integer.parseInt(headers.group(2));
                taskType = headers.group(3);
                start = headers.end();
            }
        }
        return rootTaskId >= 0 && routeTask(history, start, snapshot.length(), displayId,
                rootTaskId, taskType, coverId, snapshot);
    }

    private static boolean routeTask(Matcher history, int start, int end, int displayId,
            int rootTaskId, String taskType, int coverId, String snapshot) throws IOException {
        history.region(start, end);
        int dialogTask = -1;
        boolean otherActivity = false;
        while (history.find()) {
            boolean nativeDialog = "com.android.systemui".equals(history.group(1))
                    && (CLASS.equals(history.group(2)) || ".usb.UsbDebuggingActivity".equals(history.group(2)));
            if (nativeDialog) dialogTask = Integer.parseInt(history.group(3));
            otherActivity |= !nativeDialog;
        }
        if (dialogTask < 0) return false;
        if (displayId != coverId) {
            if (!"standard".equals(taskType) || otherActivity) {
                Log.w(TAG, "USB confirmation cannot move safely: task " + rootTaskId
                        + " type=" + taskType + " display=" + displayId);
                return true; // Never move a HOME stack or cover the confirmation with our launcher.
            }
            run("am display move-stack " + rootTaskId + " " + coverId);
            Log.i(TAG, "moved USB confirmation task " + rootTaskId + " to display " + coverId);
        } else {
            Matcher top = TOP_DIALOG.matcher(snapshot);
            while (top.find()) {
                if (Integer.parseInt(top.group(1)) == dialogTask) return true;
            }
        }
        // A wake transition or launcher crash can put Samsung HOME above a pending dialog.
        // Focus its existing task instead of recreating the Activity or its key Intent.
        run("am task focus " + dialogTask);
        return true;
    }

    private static void run(String command) throws IOException {
        String result = Root.run(command);
        if (result.contains("Error:") || result.contains("Exception")) {
            throw new IOException("USB task routing failed: " + result.trim());
        }
    }
}
