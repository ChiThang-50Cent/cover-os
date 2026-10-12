package dev.vmn.coverlauncher;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.HorizontalScrollView;

/** Horizontal container that always settles exactly on a page boundary. */
final class Pager extends HorizontalScrollView {
    interface OnPageChange {
        void onPage(int page);
    }

    private static final int FLING_MIN_VELOCITY = 400;

    private int pageWidth;
    private int pageCount = 1;
    private int page;
    private boolean flung;
    private OnPageChange listener;

    Pager(Context c) {
        super(c);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    void setPages(int width, int count) {
        pageWidth = width;
        pageCount = Math.max(1, count);
    }

    void setOnPageChange(OnPageChange l) {
        listener = l;
    }

    int page() {
        return page;
    }

    void goTo(int p, boolean animate) {
        int x = clamp(p) * pageWidth;
        if (animate) smoothScrollTo(x, 0);
        else scrollTo(x, 0);
    }

    @Override
    public void fling(int velocityX) {
        flung = true;
        settle(velocityX);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean handled = super.onTouchEvent(e);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            if (!flung) settle(0);
            flung = false;
        }
        return handled;
    }

    @Override
    protected void onScrollChanged(int l, int t, int ol, int ot) {
        super.onScrollChanged(l, t, ol, ot);
        if (pageWidth == 0) return;
        int p = clamp(Math.round((float) l / pageWidth));
        if (p != page) {
            page = p;
            if (listener != null) listener.onPage(p);
        }
    }

    private void settle(int velocityX) {
        if (pageWidth == 0) return;
        float pos = (float) getScrollX() / pageWidth;
        int target = velocityX > FLING_MIN_VELOCITY ? (int) Math.floor(pos) + 1
                : velocityX < -FLING_MIN_VELOCITY ? (int) Math.ceil(pos) - 1
                : Math.round(pos);
        smoothScrollTo(clamp(target) * pageWidth, 0);
    }

    private int clamp(int p) {
        return Math.max(0, Math.min(pageCount - 1, p));
    }
}
