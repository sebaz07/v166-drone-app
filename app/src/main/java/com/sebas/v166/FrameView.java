package com.sebas.v166;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Muestra el ultimo cuadro del dron a pantalla completa, con zoom de pellizco. */
final class FrameView extends View {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final ScaleGestureDetector scaler;
    private Bitmap frame;
    private boolean fill = true;
    private float zoom = 1f;
    private int orientation;   // 0 normal, 1 girada 180, 2 espejo, 3 volteada vertical

    FrameView(Context c) {
        super(c);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoom = Math.max(1f, Math.min(4f, zoom * d.getScaleFactor()));
                invalidate();
                return true;
            }
        });
    }

    void setFrame(Bitmap b) {
        frame = b;
        invalidate();
    }

    void setOrientation(int o) {
        orientation = o & 3;
        invalidate();
    }

    void toggleFill() {
        fill = !fill;
        invalidate();
    }

    float getZoom() { return zoom; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFF07090D);
        Bitmap b = frame;
        if (b == null || b.isRecycled()) return;
        float vw = getWidth(), vh = getHeight();
        float sx = vw / b.getWidth(), sy = vh / b.getHeight();
        float s = (fill ? Math.max(sx, sy) : Math.min(sx, sy)) * zoom;
        float w = b.getWidth() * s, h = b.getHeight() * s;
        dst.set((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f);
        c.save();
        c.scale(Orient.sx(orientation), Orient.sy(orientation), vw / 2f, vh / 2f);
        c.drawBitmap(b, null, dst, paint);
        c.restore();
    }
}
