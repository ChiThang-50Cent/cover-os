package dev.vmn.coverlauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Paged app grid sized for the 512x260 cover display. Apps open on the display this launcher is on.
 * Long-press an icon to pin it; pinned apps come first.
 */
public final class LauncherActivity extends Activity {
    private static final String PINS = "pins";
    // Sizes are dp; the cover display runs at 160 dpi so 1 dp = 1 px there.
    private static final int HEADER_DP = 28;
    private static final int EDGE_DP = 20; // matches the gesture strips in EdgeGestures
    private static final int BOTTOM_DP = 16; // matches the home-gesture strip in EdgeGestures
    private static final int MIN_CELL_W_DP = 120;
    private static final int MIN_CELL_H_DP = 100;
    private static final int ICON_DP = 72;
    private static final int PINNED_COLOR = 0xFFFFC107;
    private static final int DOT_ON = Color.WHITE;
    private static final int DOT_OFF = 0x55FFFFFF;

    private static final class App {
        ComponentName cn;
        String label;
        android.graphics.drawable.Drawable icon;
        boolean pinned;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            showBattery(i);
        }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            reload();
        }
    };

    private SharedPreferences prefs;
    private PackageManager pm;
    private float density;
    private int ripple;
    private Pager pager;
    private LinearLayout strip;
    private LinearLayout dots;
    private TextView batteryText;
    private TextClock headerClock;
    private LinearLayout notifBox;
    private List<App> apps = new ArrayList<>();
    private int pageCount = 1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        pm = getPackageManager();
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        ripple = tv.resourceId;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(EDGE_DP + 4), 0, dp(EDGE_DP + 4), 0);

        headerClock = new TextClock(this);
        headerClock.setFormat24Hour("HH:mm");
        headerClock.setFormat12Hour("HH:mm");
        headerClock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        headerClock.setTypeface(Typeface.DEFAULT_BOLD);
        headerClock.setTextColor(Color.WHITE);
        header.addView(headerClock);

        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        header.addView(dots, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        batteryText = new TextView(this);
        batteryText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        batteryText.setTextColor(Color.WHITE);
        header.addView(batteryText);

        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(HEADER_DP)));

        pager = new Pager(this);
        pager.setOnPageChange(this::updateDots);
        strip = new LinearLayout(this);
        pager.addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(pager, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        IntentFilter pkgs = new IntentFilter();
        pkgs.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgs.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgs.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkgs.addDataScheme("package");
        registerReceiver(packageReceiver, pkgs);

        reload();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent sticky = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) showBattery(sticky);
        NotifService.setListener(this::showNotifs);
        showNotifs();
    }

    @Override
    protected void onStop() {
        NotifService.setListener(null);
        unregisterReceiver(batteryReceiver);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        pager.goTo(0, true);
    }

    @Override
    public void onBackPressed() {
        // This is the home screen: Back only rewinds to the first page, it never leaves.
        if (pager.page() > 0) pager.goTo(0, true);
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private void showBattery(Intent i) {
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        batteryText.setText((charging ? "⚡" : "") + (level * 100 / scale) + "%");
    }

    private void reload() {
        io.execute(() -> {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            Set<String> pins = prefs.getStringSet(PINS, Collections.emptySet());
            List<App> list = new ArrayList<>();
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                if (ri.activityInfo.packageName.equals(getPackageName())) continue;
                App a = new App();
                a.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                a.label = ri.loadLabel(pm).toString();
                a.icon = ri.loadIcon(pm);
                a.pinned = pins.contains(a.cn.flattenToString());
                list.add(a);
            }
            sort(list);
            runOnUiThread(() -> {
                apps = list;
                render();
            });
        });
    }

    private static void sort(List<App> list) {
        Collator collator = Collator.getInstance();
        list.sort((x, y) -> x.pinned != y.pinned ? (x.pinned ? -1 : 1) : collator.compare(x.label, y.label));
    }

    private void render() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        int w = m.widthPixels;
        int h = m.heightPixels - dp(HEADER_DP) - dp(BOTTOM_DP);
        int cols = Math.max(3, w / dp(MIN_CELL_W_DP));
        int rows = Math.max(1, h / dp(MIN_CELL_H_DP));
        int perPage = cols * rows;
        int appPages = Math.max(1, (apps.size() + perPage - 1) / perPage);
        pageCount = 1 + appPages; // page 0 is the clock
        int keep = Math.min(pager.page(), pageCount - 1);

        strip.removeAllViews();
        strip.addView(buildClockPage(), new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int p = 0; p < appPages; p++) {
            strip.addView(buildPage(p, cols, rows), new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        pager.setPages(w, pageCount);
        pager.post(() -> {
            pager.goTo(keep, false);
            updateDots(keep);
        });
    }

    private View buildPage(int p, int cols, int rows) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(EDGE_DP), 0, dp(EDGE_DP), dp(BOTTOM_DP));
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            page.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            for (int c = 0; c < cols; c++) {
                int i = p * cols * rows + r * cols + c;
                View cell = i < apps.size() ? buildCell(apps.get(i)) : new View(this);
                row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
        }
        return page;
    }

    /** Idle screen: big clock and date on the left, the newest notifications on the right. */
    private View buildClockPage() {
        LinearLayout page = new LinearLayout(this);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.setPadding(dp(EDGE_DP), 0, dp(EDGE_DP), dp(BOTTOM_DP));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 64);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        time.setTextColor(Color.WHITE);
        left.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        date.setTextColor(0xFFBBBBBB);
        date.setSingleLine(true);
        left.addView(date, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        left.addView(buildCloseAll());
        page.addView(left, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        notifBox = new LinearLayout(this);
        notifBox.setOrientation(LinearLayout.VERTICAL);
        notifBox.setGravity(Gravity.CENTER_VERTICAL);
        notifBox.setPadding(dp(16), 0, 0, 0);
        page.addView(notifBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        showNotifs();
        return page;
    }

    /** Force-stops every app running on this display (the launcher itself and system UI stay). */
    private View buildCloseAll() {
        TextView b = new TextView(this);
        b.setText(R.string.close_all);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(9), dp(14), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(20));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> closeAll(b));
        return b;
    }

    private void closeAll(TextView b) {
        int display = getDisplay().getDisplayId();
        b.setEnabled(false);
        new Thread(() -> {
            int n = -1;
            try {
                n = Launch.closeApps(display, getPackageName());
            } catch (IOException e) {
                Log.e("CoverLauncher", "close all failed", e);
            }
            int count = n;
            runOnUiThread(() -> {
                b.setText(count < 0 ? getString(R.string.close_failed)
                        : count == 0 ? getString(R.string.nothing_to_close)
                        : getString(R.string.closed_fmt, count));
                b.postDelayed(() -> {
                    b.setText(R.string.close_all);
                    b.setEnabled(true);
                }, 2000);
            });
        }).start();
    }

    private void showNotifs() {
        if (notifBox == null) return;
        notifBox.removeAllViews();
        List<NotifService.Item> items = NotifService.items();
        if (items.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(R.string.no_notifications);
            none.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            none.setTextColor(0xFF888888);
            notifBox.addView(none);
            return;
        }
        for (NotifService.Item it : items) notifBox.addView(buildNotifRow(it));
    }

    private View buildNotifRow(NotifService.Item it) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        row.setBackgroundResource(ripple);
        try {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(pm.getApplicationIcon(it.pkg));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
            lp.rightMargin = dp(8);
            row.addView(icon, lp);
        } catch (PackageManager.NameNotFoundException ignored) {
            // no icon for an uninstalled sender
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(oneLine(it.title, 15, Color.WHITE));
        col.addView(oneLine(it.text, 13, 0xFFAAAAAA));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> {
            Intent i = pm.getLaunchIntentForPackage(it.pkg);
            if (i != null) Launch.activity(this, i, getDisplay().getDisplayId());
        });
        return row;
    }

    private TextView oneLine(CharSequence s, int sizeDp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    private View buildCell(App a) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setBackgroundResource(ripple);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(a.icon);
        cell.addView(icon, new LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)));

        TextView label = new TextView(this);
        label.setText(a.label);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        label.setTextColor(a.pinned ? PINNED_COLOR : Color.WHITE);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        cell.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        cell.setOnClickListener(v -> launch(a));
        cell.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            togglePin(a);
            return true;
        });
        return cell;
    }

    private void updateDots(int current) {
        dots.removeAllViews();
        headerClock.setVisibility(current == 0 ? View.INVISIBLE : View.VISIBLE); // the clock page has its own
        if (pageCount < 2) return;
        for (int i = 0; i < pageCount; i++) {
            TextView d = new TextView(this);
            d.setText("●");
            d.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
            d.setTextColor(i == current ? DOT_ON : DOT_OFF);
            d.setPadding(dp(3), 0, dp(3), 0);
            dots.addView(d);
        }
    }

    private void launch(App a) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn);
        Launch.activity(this, i, getDisplay().getDisplayId());
    }

    private void togglePin(App a) {
        Set<String> pins = new HashSet<>(prefs.getStringSet(PINS, Collections.emptySet()));
        String key = a.cn.flattenToString();
        if (!pins.remove(key)) pins.add(key);
        prefs.edit().putStringSet(PINS, pins).apply();
        a.pinned = pins.contains(key);
        sort(apps);
        render();
    }
}
