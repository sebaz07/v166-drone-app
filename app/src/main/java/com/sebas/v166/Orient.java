package com.sebas.v166;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import java.io.ByteArrayOutputStream;

/** Orientacion de la imagen: 0 normal, 1 girada 180 grados, 2 espejo horizontal, 3 volteada vertical. */
final class Orient {
    static final String[] NAMES = {"Imagen normal", "Imagen girada 180°", "Imagen en espejo", "Imagen volteada"};

    private Orient() {}

    static float sx(int o) { return (o == 1 || o == 2) ? -1f : 1f; }
    static float sy(int o) { return (o == 1 || o == 3) ? -1f : 1f; }

    /** Aplica la orientacion a un JPEG (para que la foto guardada salga como se ve). */
    static byte[] apply(byte[] jpeg, int o) {
        if (o == 0) return jpeg;
        try {
            Bitmap src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (src == null) return jpeg;
            Matrix m = new Matrix();
            m.setScale(sx(o), sy(o));
            Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, false);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, 95, bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            return jpeg;
        }
    }
}
