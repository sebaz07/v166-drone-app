package com.sebas.v166;

import android.content.Context;

/** Colores y medidas compartidas de la interfaz. */
final class Ui {
    static final int ACCENT = 0xFF3DDCFF;
    static final int DANGER = 0xFFFF4D5E;
    static final int OK = 0xFF5BE49B;
    static final int WARN = 0xFFFFC857;
    static final int PANEL = 0xB3101620;
    static final int PANEL_STROKE = 0x33FFFFFF;
    static final int TEXT = 0xFFF2F5F9;
    static final int TEXT_DIM = 0xB3F2F5F9;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
