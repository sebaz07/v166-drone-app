package com.sebas.v166;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Boton del HUD: icono dibujado a mano + etiqueta corta. */
final class HudButton extends View {
    static final int CAMERA = 0, RECORD = 1, SWITCH_CAM = 2, UP = 3, DOWN = 4, TAKEOFF = 5, STOP = 6,
            FLIP = 7, CALIBRATE = 8, HEADLESS = 9, TEXT = 10, PILOT = 11, HAND = 12;

    interface OnHold { void onHold(boolean down); }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final int icon;
    private String label;
    private String bigText = "";
    private boolean active;
    private boolean pressedNow;
    private int tint = Ui.ACCENT;
    private OnHold hold;

    HudButton(Context c, int icon, String label) {
        super(c);
        this.icon = icon;
        this.label = label;
        setClickable(true);
        setContentDescription(label);
    }

    void setLabel(String s) { label = s; setContentDescription(s); invalidate(); }
    void setBigText(String s) { bigText = s; invalidate(); }
    void setActive(boolean a) { if (active != a) { active = a; invalidate(); } }
    boolean isActive() { return active; }
    void setTint(int color) { tint = color; invalidate(); }
    void setOnHold(OnHold h) { hold = h; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedNow = true;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (hold != null) hold.onHold(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (pressedNow) {
                    pressedNow = false;
                    if (hold != null) hold.onHold(false);
                    invalidate();
                    if (e.getX() >= 0 && e.getX() <= getWidth() && e.getY() >= 0 && e.getY() <= getHeight()) {
                        performClick();
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressedNow = false;
                if (hold != null) hold.onHold(false);
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        float radius = 16 * d;
        boolean stop = icon == STOP;
        int color = active ? tint : Ui.TEXT;

        r.set(d, d, w - d, h - d);
        p.setStyle(Paint.Style.FILL);
        if (stop) p.setColor(pressedNow ? 0xFFB3202F : 0xE6E0283A);
        else if (pressedNow) p.setColor(0xE6263447);
        else if (active) p.setColor(0xD9122433);
        else p.setColor(Ui.PANEL);
        c.drawRoundRect(r, radius, radius, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f * d);
        p.setColor(active && !stop ? tint : Ui.PANEL_STROKE);
        c.drawRoundRect(r, radius, radius, p);

        boolean hasLabel = label != null && label.length() > 0;
        float labelH = hasLabel ? 15 * d : 0;
        float cx = w / 2f, cy = (h - labelH) / 2f + (hasLabel ? 2 * d : 0);
        float s = Math.min(w, h - labelH) * 0.26f;   // medio tamano del icono

        p.setAlpha(255);
        p.setColor(stop ? 0xFFFFFFFF : color);
        p.setStrokeWidth(2.2f * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStyle(Paint.Style.STROKE);
        if (!isEnabled()) p.setAlpha(90);
        path.reset();
        switch (icon) {
            case CAMERA:
                r.set(cx - s, cy - s * 0.62f, cx + s, cy + s * 0.78f);
                c.drawRoundRect(r, s * 0.22f, s * 0.22f, p);
                c.drawCircle(cx, cy + s * 0.08f, s * 0.36f, p);
                c.drawLine(cx - s * 0.35f, cy - s * 0.62f, cx - s * 0.2f, cy - s * 0.9f, p);
                c.drawLine(cx - s * 0.2f, cy - s * 0.9f, cx + s * 0.2f, cy - s * 0.9f, p);
                c.drawLine(cx + s * 0.2f, cy - s * 0.9f, cx + s * 0.35f, cy - s * 0.62f, p);
                break;
            case RECORD:
                p.setStyle(Paint.Style.FILL);
                p.setColor(Ui.DANGER);
                if (!isEnabled()) p.setAlpha(90);
                if (active) {
                    r.set(cx - s * 0.6f, cy - s * 0.6f, cx + s * 0.6f, cy + s * 0.6f);
                    c.drawRoundRect(r, s * 0.18f, s * 0.18f, p);
                } else {
                    c.drawCircle(cx, cy, s * 0.72f, p);
                }
                p.setStyle(Paint.Style.STROKE);
                p.setColor(Ui.TEXT);
                c.drawCircle(cx, cy, s, p);
                break;
            case SWITCH_CAM:
                path.moveTo(cx - s, cy - s * 0.4f); path.lineTo(cx + s * 0.8f, cy - s * 0.4f);
                path.moveTo(cx + s * 0.35f, cy - s * 0.85f); path.lineTo(cx + s * 0.8f, cy - s * 0.4f);
                path.lineTo(cx + s * 0.35f, cy + s * 0.05f);
                path.moveTo(cx + s, cy + s * 0.45f); path.lineTo(cx - s * 0.8f, cy + s * 0.45f);
                path.moveTo(cx - s * 0.35f, cy); path.lineTo(cx - s * 0.8f, cy + s * 0.45f);
                path.lineTo(cx - s * 0.35f, cy + s * 0.9f);
                c.drawPath(path, p);
                break;
            case UP:
                path.moveTo(cx - s * 0.8f, cy + s * 0.4f); path.lineTo(cx, cy - s * 0.4f);
                path.lineTo(cx + s * 0.8f, cy + s * 0.4f);
                c.drawPath(path, p);
                break;
            case DOWN:
                path.moveTo(cx - s * 0.8f, cy - s * 0.4f); path.lineTo(cx, cy + s * 0.4f);
                path.lineTo(cx + s * 0.8f, cy - s * 0.4f);
                c.drawPath(path, p);
                break;
            case TAKEOFF:
                path.moveTo(cx, cy + s * 0.55f); path.lineTo(cx, cy - s * 0.9f);
                path.moveTo(cx - s * 0.55f, cy - s * 0.35f); path.lineTo(cx, cy - s * 0.9f);
                path.lineTo(cx + s * 0.55f, cy - s * 0.35f);
                path.moveTo(cx - s, cy + s * 0.95f); path.lineTo(cx + s, cy + s * 0.95f);
                c.drawPath(path, p);
                break;
            case FLIP:
                r.set(cx - s * 0.85f, cy - s * 0.85f, cx + s * 0.85f, cy + s * 0.85f);
                c.drawArc(r, -60, 300, false, p);
                path.moveTo(cx + s * 0.05f, cy - s * 1.15f); path.lineTo(cx + s * 0.48f, cy - s * 0.74f);
                path.lineTo(cx - s * 0.1f, cy - s * 0.5f);
                c.drawPath(path, p);
                break;
            case CALIBRATE:
                c.drawCircle(cx, cy, s * 0.6f, p);
                c.drawLine(cx - s, cy, cx - s * 0.25f, cy, p);
                c.drawLine(cx + s * 0.25f, cy, cx + s, cy, p);
                c.drawLine(cx, cy - s, cx, cy - s * 0.25f, p);
                c.drawLine(cx, cy + s * 0.25f, cx, cy + s, p);
                break;
            case HEADLESS:
                c.drawCircle(cx, cy, s, p);
                path.moveTo(cx, cy - s * 0.65f); path.lineTo(cx + s * 0.32f, cy + s * 0.5f);
                path.lineTo(cx, cy + s * 0.22f); path.lineTo(cx - s * 0.32f, cy + s * 0.5f); path.close();
                p.setStyle(Paint.Style.FILL);
                c.drawPath(path, p);
                break;
            case PILOT:
                r.set(cx - s * 1.15f, cy - s * 0.65f, cx + s * 1.15f, cy + s * 0.65f);
                c.drawRoundRect(r, s * 0.6f, s * 0.6f, p);
                c.drawLine(cx - s * 0.75f, cy, cx - s * 0.3f, cy, p);
                c.drawLine(cx - s * 0.525f, cy - s * 0.22f, cx - s * 0.525f, cy + s * 0.22f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx + s * 0.5f, cy, s * 0.14f, p);
                break;
            case HAND:
                // palma abierta simplificada
                for (int i = 0; i < 4; i++) {
                    float fx = cx - s * 0.6f + i * s * 0.4f;
                    c.drawLine(fx, cy + s * 0.1f, fx, cy - s * (i == 1 || i == 2 ? 0.95f : 0.75f), p);
                }
                c.drawLine(cx - s * 0.95f, cy + s * 0.35f, cx - s * 0.6f, cy + s * 0.1f, p);
                r.set(cx - s * 0.7f, cy - s * 0.05f, cx + s * 0.7f, cy + s * 0.95f);
                c.drawRoundRect(r, s * 0.35f, s * 0.35f, p);
                break;
            case STOP:
            case TEXT:
            default:
                p.setStyle(Paint.Style.FILL);
                p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                p.setTextAlign(Paint.Align.CENTER);
                String t = stop ? "STOP" : bigText;
                p.setTextSize(stop ? 17 * d : 15 * d);
                c.drawText(t, cx, cy + p.getTextSize() * 0.36f, p);
                p.setTypeface(Typeface.DEFAULT);
                break;
        }

        if (hasLabel) {
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.DEFAULT);
            p.setColor(stop ? 0xFFFFFFFF : (active ? tint : Ui.TEXT_DIM));
            if (!isEnabled()) p.setAlpha(90);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(10 * d);
            c.drawText(label, cx, h - 8 * d, p);
        }
    }
}
