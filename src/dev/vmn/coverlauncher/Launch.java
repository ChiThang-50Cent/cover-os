package dev.vmn.coverlauncher;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Starting and stopping activities on a specific display, and finding the cover display. */
final class Launch {
    private static final String TAG = "CoverLauncher";
    private static final Pattern DISPLAY = Pattern.compile(
            "^\\s*mBaseDisplayInfo=DisplayInfo\\{[^\\n]*?displayId (\\d+),[^\\n]*? real (\\d+) x (\\d+),[^\\n]*? type INTERNAL,",
            Pattern.MULTILINE);
    private static final Pattern ACTIVITY_PKG = Pattern.compile("ActivityRecord\\{[0-9a-f]+ u\\d+ ([A-Za-z0-9._]+)/");
    private static final int FALLBACK_COVER_ID = 1;

    private static volatile int coverId = -1;

    private Launch() {}

    /**
     * Id of the cover display: the smallest non-default internal display. The system dump includes
     * hidden displays; virtual displays (for example scrcpy) must never be selected or cached.
     * Blocking on first call; call off the main thread.
     */
    static int coverId() {
        if (coverId >= 0) return coverId;
        try {
            Matcher m = DISPLAY.matcher(Root.run("dumpsys display"));
            long bestArea = Long.MAX_VALUE;
            while (m.find()) {
                int id = Integer.parseInt(m.group(1));
                long area = Long.parseLong(m.group(2)) * Long.parseLong(m.group(3));
                if (id != 0 && area < bestArea) {
                    bestArea = area;
                    coverId = id;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "cannot query displays", e);
        }
        return coverId >= 0 ? coverId : FALLBACK_COVER_ID;
    }

    /** The id found by {@link #coverId()}, or the fallback if that has not run yet. Never blocks. */
    static int cachedCoverId() {
        return coverId >= 0 ? coverId : FALLBACK_COVER_ID;
    }

    /** Starts {@code intent} on {@code display}; falls back to a root {@code am start} if the framework refuses. */
    static void activity(Context c, Intent intent, int display) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            c.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());
        } catch (RuntimeException e) {
            Log.w(TAG, "startActivity on display " + display + " refused, using root", e);
            ComponentName cn = intent.getComponent();
            new Thread(() -> {
                try {
                    root(cn, display);
                } catch (IOException ex) {
                    Log.e(TAG, "root launch failed", ex);
                }
            }).start();
        }
    }

    /** Blocking; call off the main thread. */
    static void root(ComponentName cn, int display) throws IOException {
        Root.run("am start --user 0 --display " + display + " -n '" + cn.flattenToShortString() + "'");
    }

    /**
     * Force-stops every app that has an activity on {@code display}, except {@code keep} and the system UI.
     * Returns how many were stopped. Blocking; call off the main thread.
     */
    static int closeApps(int display, String keep) throws IOException {
        String out = Root.run("dumpsys activity activities | sed -n '/^Display #" + display + " /,/^Display #/p'");
        Set<String> pkgs = new LinkedHashSet<>();
        Matcher m = ACTIVITY_PKG.matcher(out);
        while (m.find()) {
            String pkg = m.group(1);
            if (!pkg.equals(keep) && !pkg.equals("com.android.systemui")) pkgs.add(pkg);
        }
        if (pkgs.isEmpty()) return 0;
        StringBuilder cmd = new StringBuilder();
        for (String pkg : pkgs) cmd.append("am force-stop ").append(pkg).append(";");
        Root.run(cmd.toString());
        return pkgs.size();
    }
}
