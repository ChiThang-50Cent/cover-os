package dev.vmn.coverlauncher;

import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Feeds the newest notifications to the clock page of {@link LauncherActivity}. Needs notification access. */
public final class NotifService extends NotificationListenerService {
    static final class Item {
        final String pkg;
        final CharSequence title;
        final CharSequence text;

        Item(String pkg, CharSequence title, CharSequence text) {
            this.pkg = pkg;
            this.title = title;
            this.text = text;
        }
    }

    private static final int MAX_ITEMS = 3;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile List<Item> items = Collections.emptyList();
    private static volatile Runnable listener;

    static List<Item> items() {
        return items;
    }

    /** {@code r} runs on the main thread whenever the list changes. */
    static void setListener(Runnable r) {
        listener = r;
    }

    @Override
    public void onListenerConnected() {
        refresh();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        refresh();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        refresh();
    }

    private void refresh() {
        StatusBarNotification[] active = getActiveNotifications();
        if (active == null) return;
        Arrays.sort(active, (a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        List<Item> out = new ArrayList<>();
        for (StatusBarNotification s : active) {
            Notification n = s.getNotification();
            if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_GROUP_SUMMARY)) != 0) continue;
            CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            if (title == null && text == null) continue;
            out.add(new Item(s.getPackageName(), title, text));
            if (out.size() == MAX_ITEMS) break;
        }
        items = out;
        Runnable r = listener;
        if (r != null) MAIN.post(r);
    }
}
