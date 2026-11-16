package com.sebas.v166;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Guarda fotos y videos en la galeria del telefono, carpeta "DronV166". */
final class MediaSaver {
    static final String ALBUM = "DronV166";

    private MediaSaver() {}

    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    static File tempVideo(Context c) {
        File dir = c.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = c.getFilesDir();
        dir.mkdirs();
        return new File(dir, "V166_" + stamp() + ".mp4");
    }

    /** Devuelve una descripcion de donde quedo, o null si fallo. */
    static String savePhoto(Context c, byte[] jpeg) {
        String name = "V166_" + stamp() + ".jpg";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                v.put("relative_path", Environment.DIRECTORY_PICTURES + "/" + ALBUM);
                v.put("is_pending", 1);
                ContentResolver r = c.getContentResolver();
                Uri uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                OutputStream os = r.openOutputStream(uri);
                try { os.write(jpeg); } finally { os.close(); }
                v.clear();
                v.put("is_pending", 0);
                r.update(uri, v, null, null);
                return "Galería › " + ALBUM;
            }
            File dir = new File(c.getExternalFilesDir(Environment.DIRECTORY_PICTURES), ALBUM);
            dir.mkdirs();
            File f = new File(dir, name);
            FileOutputStream os = new FileOutputStream(f);
            try { os.write(jpeg); } finally { os.close(); }
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /** Mueve el MP4 temporal a la galeria. Devuelve una descripcion de donde quedo. */
    static String publishVideo(Context c, File mp4) {
        if (mp4 == null || !mp4.exists()) return null;
        if (Build.VERSION.SDK_INT < 29) return mp4.getAbsolutePath();
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, mp4.getName());
            v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            v.put("relative_path", Environment.DIRECTORY_MOVIES + "/" + ALBUM);
            v.put("is_pending", 1);
            ContentResolver r = c.getContentResolver();
            Uri uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return mp4.getAbsolutePath();
            InputStream in = new FileInputStream(mp4);
            OutputStream os = r.openOutputStream(uri);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } finally {
                os.close();
                in.close();
            }
            v.clear();
            v.put("is_pending", 0);
            r.update(uri, v, null, null);
            mp4.delete();
            return "Galería › " + ALBUM;
        } catch (Exception e) {
            return mp4.getAbsolutePath();
        }
    }
}
