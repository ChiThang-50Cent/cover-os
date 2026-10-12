package dev.vmn.coverlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Invisible overlay strips on the cover display: swipe in from the left/right edge = Back,
 * swipe up from the bottom edge = Home. Needs the SYSTEM_ALERT_WINDOW permission.
 */
final class EdgeGestures {
    interface Listener {
        void onBack();

        void onHome();
    }

    private static final int EDGE_DP = 20; // keep in sync with LauncherActivity.EDGE_DP
    private static final int BOTTOM_DP = 16;
    private static final int TRIGGER_DP = 40;

    private final WindowManager wm;
    private final List<View> views = new ArrayList<>();

    EdgeGestures(Context service, Display display, Listener listener) {
        Context wc = service.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        wm = wc.getSystemService(WindowManager.class);
        float d = wc.getResources().getDisplayMetrics().density;
        int edge = Math.round(EDGE_DP * d);
        int bottom = Math.round(BOTTOM_DP * d);
        int trigger = Math.round(TRIGGER_DP * d);

        add(new Strip(wc, 1, 0, trigger, false, listener::onBack),
                edge, WindowManager.LayoutParams.MATCH_PARENT, Gravity.LEFT | Gravity.TOP);
        add(new Strip(wc, -1, 0, trigger, false, listener::onBack),
                edge, WindowManager.LayoutParams.MATCH_PARENT, Gravity.RIGHT | Gravity.TOP);
        add(new Strip(wc, 0, -1, trigger, true, listener::onHome),
                WindowManager.LayoutParams.MATCH_PARENT, bottom, Gravity.BOTTOM | Gravity.LEFT);
    }

    void detach() {
        for (View v : views) {
            try {
                wm.removeView(v);
            } catch (RuntimeException ignored) {
                // already gone with the display
            }
        }
        views.clear();
    }

    private void add(View v, int w, int h, int gravity) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        wm.addView(v, lp);
        views.add(v);
    }

    /** Fires once per touch when the finger travels {@code trigger} px along (dirX, dirY) and mostly straight. */
    private static final class Strip extends View {
        private final int dirX, dirY, trigger;
        private final boolean handle;
        private final Runnable action;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x0, y0;
        private boolean fired;

        Strip(Context c, int dirX, int dirY, int trigger, boolean handle, Runnable action) {
            super(c);
            this.dirX = dirX;
            this.dirY = dirY;
            this.trigger = trigger;
            this.handle = handle;
            this.action = action;
            paint.setColor(0xCCFFFFFF);
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(2);
            outline.setColor(0x99000000);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!handle) return;
            float w = getWidth() * 0.12f, h = getHeight() * 0.25f;
            float cx = getWidth() / 2f, cy = getHeight() - h * 2f;
            RectF pill = new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            canvas.drawRoundRect(pill, h / 2, h / 2, paint);
            canvas.drawRoundRect(pill, h / 2, h / 2, outline);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    fired = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                    float along = dx * dirX + dy * dirY;
                    float across = Math.abs(dx * dirY) + Math.abs(dy * dirX);
                    if (!fired && along >= trigger && along > across) {
                        fired = true;
                        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                        action.run();
                    }
                    return true;
                default:
                    return true;
            }
        }
    }
}
