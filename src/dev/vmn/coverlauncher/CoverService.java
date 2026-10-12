package dev.vmn.coverlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import android.view.Display;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the cover display usable: draws the edge-gesture strips, injects Back/Home through root,
 * and replaces Samsung's stock cover home with {@link LauncherActivity} whenever that home comes up.
 */
public final class CoverService extends Service implements DisplayManager.DisplayListener {
    static final String EXTRA_SHOW = "show";

    private static final String TAG = "CoverLauncher";
    private static final String CHANNEL = "cover";
    private static final String STOCK_HOME = "com.android.systemui.subscreen.SubHomeActivity";
    private static final Pattern EVENT_COMPONENT = Pattern.compile("\\[-?\\d+,([^,\\]]+),"); // [token,component,reason]
    private static final Pattern COMPONENT_IN_EVENT = Pattern.compile("[A-Za-z0-9._]+/([.A-Za-z0-9._$]+)"); // pkg/Class
    private static final Pattern TOP_STOCK_HOME = Pattern.compile(
            "topResumedActivity=ActivityRecord\\{[^\\n]*com\\.android\\.systemui/(?:\\.subscreen\\.SubHomeActivity|"
                    + "com\\.android\\.systemui\\.subscreen\\.SubHomeActivity) ");
    private static final long MIN_RELAUNCH_MS = 1500; // stops a crash-looping launcher from spinning
    private static final long UNLOCK_DELAY_MS = 800; // let the wake/fold transition finish before checking
    private static final long HEALTH_INTERVAL_MS = 5000;
    private static final String STATE_SNAPSHOT =
            "dumpsys device_state; dumpsys power; dumpsys window policy; dumpsys activity activities";
    private static final Pattern KEYGUARD_SHOWING = Pattern.compile("(?m)^ *showing=true *$");
    private static final Pattern KEYGUARD_SWIPE_ONLY = Pattern.compile("(?m)^ *secure=false *$");

    private HandlerThread thread;
    private Handler worker;
    private Handler main;
    private DisplayManager dm;
    private EdgeGestures gestures;
    private volatile boolean running = true;
    private volatile Process watcher;
    private long lastLaunch;
    private final AtomicBoolean callBusy = new AtomicBoolean();
    private final Runnable unlockTask = this::reconcileSafely;
    private final AtomicBoolean healthBusy = new AtomicBoolean();
    private final Runnable healthTask = this::checkHealth;
    private AtomicFile heartbeat;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setStyle(new Notification.BigTextStyle().bigText(getString(R.string.notif_text)))
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);

        thread = new HandlerThread("cover-worker");
        thread.start();
        worker = new Handler(thread.getLooper());
        main = new Handler(getMainLooper());
        dm = getSystemService(DisplayManager.class);
        dm.registerDisplayListener(this, worker);

        main.post(this::attachGestures);
        new Thread(this::watchStockHome, "cover-watcher").start();
        heartbeat = new AtomicFile(new File(getFilesDir(), "heartbeat"));
        main.post(healthTask);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(EXTRA_SHOW, false)) worker.post(this::showLauncher);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacks(healthTask);
        worker.removeCallbacksAndMessages(null);
        Process p = watcher;
        if (p != null) p.destroy();
        dm.unregisterDisplayListener(this);
        main.post(this::detachGestures);
        thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDisplayAdded(int id) {
        main.post(this::attachGestures);
    }

    @Override
    public void onDisplayChanged(int id) {
    }

    @Override
    public void onDisplayRemoved(int id) {
        main.post(this::detachGestures);
    }

    /**
     * Blocks on system events; the health tick also reconciles missed events and restart state.
     * <ul>
     * <li>Samsung's cover home became the top activity: start our launcher (an app in front is never disturbed).</li>
     * <li>Screen toggled or keyguard shown: if the phone is folded and awake, swipe the keyguard away.</li>
     * </ul>
     */
    private void watchStockHome() {
        while (running) {
            try {
                Process p = new ProcessBuilder("su", "-c",
                        "exec logcat -b events -T 1 -s wm_on_top_resumed_gained_called wm_create_activity wm_new_intent"
                                + " screen_toggled wm_set_keyguard_shown")
                        .redirectErrorStream(true).start();
                watcher = p;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    for (String line; running && (line = r.readLine()) != null; ) {
                        if (line.contains("wm_on_top_resumed_gained_called")) {
                            Matcher c = EVENT_COMPONENT.matcher(line);
                            if (c.find() && (c.group(1).equals(STOCK_HOME)
                                    || c.group(1).equals(UsbDebugging.CLASS))) worker.post(this::reconcileSafely);
                        } else if (line.contains("wm_create_activity") || line.contains("wm_new_intent")) {
                            Matcher c = COMPONENT_IN_EVENT.matcher(line);
                            if (c.find()) {
                                String cls = c.group(1);
                                if (cls.equals(UsbDebugging.CLASS) || cls.equals(".usb.UsbDebuggingActivity")) {
                                    worker.postDelayed(this::reconcileSafely, 200);
                                } else if (CallAssist.CALL_ACTIVITY.matcher(cls).find()) {
                                    pullCall(cls);
                                }
                            }
                        } else {
                            worker.removeCallbacks(unlockTask);
                            worker.postDelayed(unlockTask, UNLOCK_DELAY_MS);
                        }
                    }
                }
                p.destroy();
            } catch (IOException e) {
                Log.e(TAG, "event watcher failed", e);
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Own thread: switching camera waits on the call UI for several seconds and must not delay Back/Home. */
    private void pullCall(String cls) {
        Log.i(TAG, "call-like activity event: " + cls + " busy=" + callBusy.get());
        if (!callBusy.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                CallAssist.pullToCover(cls, Launch.coverId());
            } catch (IOException e) {
                Log.e(TAG, "call assist failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                callBusy.set(false);
            }
        }, "call-assist").start();
    }

    /** Only a responsive main loop, worker, root shell and event watcher publish health. */
    private void checkHealth() {
        if (!running) return;
        if (healthBusy.compareAndSet(false, true)) {
            worker.post(() -> {
                try {
                    reconcile();
                    Process p = watcher;
                    if (p != null && p.isAlive()) writeHeartbeat();
                } catch (IOException e) {
                    Log.e(TAG, "health check failed", e);
                } finally {
                    healthBusy.set(false);
                }
            });
        }
        main.postDelayed(healthTask, HEALTH_INTERVAL_MS);
    }

    private void writeHeartbeat() throws IOException {
        FileOutputStream out = heartbeat.startWrite();
        try {
            out.write((SystemClock.elapsedRealtime() / 1000 + "\n").getBytes(StandardCharsets.US_ASCII));
            heartbeat.finishWrite(out);
        } catch (IOException e) {
            heartbeat.failWrite(out);
            throw e;
        }
    }

    private void reconcileSafely() {
        try {
            reconcile();
        } catch (IOException e) {
            Log.e(TAG, "cover reconciliation failed", e);
        }
    }

    /** Reconcile existing state too, so restart or a missed log event cannot strand a dialog. */
    private void reconcile() throws IOException {
        String state = Root.run(STATE_SNAPSHOT);
        if (!state.contains("mCommittedState=") || !state.contains("ACTIVITY MANAGER ACTIVITIES")) {
            throw new IOException("incomplete cover state dump");
        }
        // Samsung's cover keyguard must be checked through its actual window policy.
        // Only dismiss a swipe lock while folded and awake; never a PIN/pattern.
        if (UsbDebugging.isFolded(state) && state.contains("mWakefulness=Awake")
                && KEYGUARD_SHOWING.matcher(state).find()
                && KEYGUARD_SWIPE_ONLY.matcher(state).find()) {
            Root.run("wm dismiss-keyguard");
            Log.i(TAG, "dismissed cover keyguard");
        }
        int coverId = Launch.coverId();
        if (UsbDebugging.route(state, coverId)) return;
        int start = state.indexOf("Display #" + coverId + " ");
        if (start < 0) return;
        int end = state.indexOf("\nDisplay #", start + 1);
        Matcher home = TOP_STOCK_HOME.matcher(state);
        home.region(start, end < 0 ? state.length() : end);
        if (home.find()) launchLauncher(coverId);
    }

    private void showLauncher() {
        try {
            int coverId = Launch.coverId();
            String state = Root.run("dumpsys device_state; dumpsys activity activities");
            if (!UsbDebugging.route(state, coverId)) launchLauncher(coverId);
        } catch (IOException e) {
            Log.e(TAG, "showLauncher failed", e);
        }
    }

    private void launchLauncher(int coverId) throws IOException {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < MIN_RELAUNCH_MS) return;
        lastLaunch = now;
        Log.i(TAG, "starting cover launcher");
        Launch.root(new ComponentName(this, LauncherActivity.class), coverId);
        main.postDelayed(this::attachGestures, 800);
    }

    private void attachGestures() {
        if (gestures != null) return;
        Display cover = dm.getDisplay(Launch.cachedCoverId());
        if (cover == null) return;
        try {
            gestures = new EdgeGestures(this, cover, new EdgeGestures.Listener() {
                @Override
                public void onBack() {
                    worker.post(() -> inject("input -d " + cover.getDisplayId() + " keyevent 4"));
                }

                @Override
                public void onHome() {
                    worker.post(CoverService.this::showHome);
                }
            });
        } catch (RuntimeException e) {
            Log.e(TAG, "cannot draw gesture strips (overlay permission missing?)", e);
        }
    }

    private void detachGestures() {
        if (gestures != null) gestures.detach();
        gestures = null;
    }

    /** Home gesture: unlike {@link #showLauncher} this is user intent, so it is never rate limited. */
    private void showHome() {
        try {
            Launch.root(new ComponentName(this, LauncherActivity.class), Launch.coverId());
        } catch (IOException e) {
            Log.e(TAG, "home failed", e);
        }
    }

    private void inject(String cmd) {
        try {
            Root.run(cmd);
        } catch (IOException e) {
            Log.e(TAG, "root command failed: " + cmd, e);
        }
    }
}
