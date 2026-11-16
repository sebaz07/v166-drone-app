package com.sebas.v166;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Palanca virtual. Vuelve sola al centro al soltarla. Valores de -1 a 1 (arriba = positivo). */
final class JoystickView extends View {
    interface OnMove { void onMove(float x, float y); }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String label;
    private OnMove listener;
    private float x, y;
    private int pointer = -1;

    JoystickView(Context c, String label) {
        super(c);
        this.label = label;
    }

    void setOnMove(OnMove l) { listener = l; }

    void reset() {
        pointer = -1;
        set(0, 0);
    }

    private void set(float nx, float ny) {
        x = nx;
        y = ny;
        if (listener != null) listener.onMove(x, y);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float r = Math.min(getWidth(), getHeight()) / 2f * 0.62f;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer = e.getPointerId(0);
                getParent().requestDisallowInterceptTouchEvent(true);
                // sigue al caso MOVE
            case MotionEvent.ACTION_MOVE: {
                int i = e.findPointerIndex(pointer);
                if (i < 0) return true;
                float dx = (e.getX(i) - getWidth() / 2f) / r;
                float dy = (getHeight() / 2f - e.getY(i)) / r;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > 1f) { dx /= len; dy /= len; }
                // zona muerta pequena para que el centro sea estable
                if (Math.abs(dx) < 0.08f) dx = 0;
                if (Math.abs(dy) < 0.08f) dy = 0;
                set(dx, dy);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                reset();
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float base = Math.min(cx, cy) * 0.94f;
        float r = Math.min(cx, cy) * 0.62f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x59101620);
        c.drawCircle(cx, cy, base, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(getContext(), 1.5f));
        p.setColor(0x55FFFFFF);
        c.drawCircle(cx, cy, base, p);
        p.setColor(0x26FFFFFF);
        c.drawLine(cx - base * 0.7f, cy, cx + base * 0.7f, cy, p);
        c.drawLine(cx, cy - base * 0.7f, cx, cy + base * 0.7f, p);

        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.TEXT_DIM);
        p.setTextSize(Ui.dp(getContext(), 10));
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(Ui.TEXT);
        p.setShadowLayer(Ui.dp(getContext(), 3), 0, Ui.dp(getContext(), 1), 0xFF000000);
        c.drawText(label, cx, cy + base - Ui.dp(getContext(), 8), p);
        p.clearShadowLayer();

        float kx = cx + x * r, ky = cy - y * r;
        boolean active = pointer >= 0;
        p.setColor(active ? 0x663DDCFF : 0x40FFFFFF);
        c.drawCircle(kx, ky, base * 0.40f, p);
        p.setColor(active ? Ui.ACCENT : 0xE6FFFFFF);
        c.drawCircle(kx, ky, base * 0.28f, p);
    }
}
