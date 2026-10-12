package dev.vmn.coverlauncher;

import android.util.Log;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Folded phone, call app: a call screen that opened on the dark main display is moved to the cover display,
 * and a video call using the front camera (which faces the inner screen when folded) is switched to the rear one.
 */
final class CallAssist {
    private static final String TAG = "CoverLauncher";

    /** Activity class names that look like an in-call screen (Messenger InCallActivity, VoIP/video call screens). */
    static final Pattern CALL_ACTIVITY = Pattern.compile("(?i)incall|voip|videocall");

    private static final Pattern TASK_ID = Pattern.compile(" t(\\d+)$", Pattern.MULTILINE);
    // Vietnamese Messenger accessibility labels. Other languages/apps are not handled.
    private static final String FRONT_CAMERA = "Đang hiển thị camera trước";
    private static final Pattern FLIP_BUTTON = Pattern.compile(
            "content-desc=\"Chuyển sang camera sau\"[^>]*?bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"");
    private static final int LOOKUP_ATTEMPTS = 4;
    private static final long LOOKUP_INTERVAL_MS = 500; // the activity record appears a moment after its create event
    private static final int FLIP_ATTEMPTS = 4; // the first tap may only reveal the auto-hidden controls
    private static final long FLIP_INTERVAL_MS = 1500;

    private CallAssist() {}

    /** Blocking; call off the main thread. Does nothing unless the phone is folded and the call is on the main display. */
    static void pullToCover(String activityClass, int coverId) throws IOException, InterruptedException {
        boolean folded = Root.run("dumpsys device_state | grep -q 'mCommittedState=.*name=.CLOSED' && echo closed")
                .contains("closed");
        Log.i(TAG, "call screen " + activityClass + " seen, folded=" + folded);
        if (!folded) return;

        String simple = activityClass.substring(activityClass.lastIndexOf('.') + 1).replace("$", "\\$");
        Matcher task = null;
        for (int i = 0; i < LOOKUP_ATTEMPTS && task == null; i++) {
            Thread.sleep(LOOKUP_INTERVAL_MS);
            // Only the "Display #0" block: lines up to the next unindented line.
            Matcher m = TASK_ID.matcher(Root.run("dumpsys activity activities | sed -n '/^Display #0 /,/^[^ ]/p'"
                    + " | grep -oE 'ActivityRecord\\{[0-9a-f]+ u[0-9]+ [A-Za-z0-9._]+/[A-Za-z0-9._$]*" + simple
                    + " t[0-9]+'"));
            if (m.find()) task = m;
        }
        if (task == null) {
            Log.i(TAG, "call screen is not on the main display, nothing to move");
            return;
        }
        Log.i(TAG, "moving call task " + task.group(1) + " to display " + coverId);
        Root.run("am display move-stack " + task.group(1) + " " + coverId);
        useRearCamera(coverId);
    }

    private static void useRearCamera(int display) throws IOException, InterruptedException {
        for (int i = 0; i < FLIP_ATTEMPTS; i++) {
            Thread.sleep(FLIP_INTERVAL_MS);
            // </dev/null: a command inheriting the shell's stdin would swallow our end-of-output marker and hang Root.
            String ui = Root.run("timeout 10 uiautomator dump --display " + display
                    + " /sdcard/.cover-ui.xml </dev/null >/dev/null 2>&1; cat /sdcard/.cover-ui.xml");
            Matcher b = FLIP_BUTTON.matcher(ui);
            if (!ui.contains(FRONT_CAMERA) || !b.find()) return;
            int x = (Integer.parseInt(b.group(1)) + Integer.parseInt(b.group(3))) / 2;
            int y = (Integer.parseInt(b.group(2)) + Integer.parseInt(b.group(4))) / 2;
            Log.i(TAG, "call uses the front camera, tapping switch-camera at " + x + "," + y);
            Root.run("input -d " + display + " tap " + x + " " + y);
        }
    }
}
