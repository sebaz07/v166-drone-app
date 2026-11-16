package com.sebas.v166;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;

/** Graba el video en vivo a un MP4 (H.264) en el celular. */
final class Recorder {
    private final Object lock = new Object();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private MediaCodec codec;
    private MediaMuxer muxer;
    private Surface surface;
    private Thread drain;
    private Rect dst;
    private File file;
    private volatile boolean active;
    private boolean muxing;
    private int track = -1;
    private int written;
    private volatile int orientation;

    void setOrientation(int o) { orientation = o & 3; }

    boolean isActive() { return active; }

    boolean start(File out, int width, int height) {
        synchronized (lock) {
            if (active) return true;
            try {
                int w = width & ~1, h = height & ~1;
                MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                f.setInteger(MediaFormat.KEY_BIT_RATE, 3000000);
                f.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
                f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                surface = codec.createInputSurface();
                codec.start();
                muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                dst = new Rect(0, 0, w, h);
                file = out;
                track = -1;
                muxing = false;
                written = 0;
                active = true;
                drain = new Thread(new Runnable() {
                    @Override public void run() { drainLoop(); }
                }, "recorder");
                drain.start();
                return true;
            } catch (Exception e) {
                releaseQuietly();
                return false;
            }
        }
    }

    /** Dibuja un cuadro en el codificador. Se llama desde el hilo que decodifica. */
    void draw(Bitmap b) {
        synchronized (lock) {
            if (!active || surface == null || b == null || b.isRecycled()) return;
            try {
                Canvas c = surface.lockHardwareCanvas();
                try {
                    c.save();
                    c.scale(Orient.sx(orientation), Orient.sy(orientation), dst.width() / 2f, dst.height() / 2f);
                    c.drawBitmap(b, null, dst, paint);
                    c.restore();
                } finally {
                    surface.unlockCanvasAndPost(c);
                }
            } catch (Exception ignored) {
                // un cuadro fallido no detiene la grabacion
            }
        }
    }

    /** Termina la grabacion. Devuelve el archivo, o null si no se grabo nada util. */
    File stop() {
        Thread t;
        synchronized (lock) {
            if (!active) return null;
            active = false;
            try { codec.signalEndOfInputStream(); } catch (Exception ignored) { }
            t = drain;
        }
        if (t != null) {
            try { t.join(3000); } catch (InterruptedException ignored) { }
        }
        synchronized (lock) {
            boolean ok = muxing && written > 0;
            releaseQuietly();
            File f = file;
            file = null;
            if (!ok && f != null) { f.delete(); return null; }
            return f;
        }
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        MediaCodec c = codec;
        try {
            while (true) {
                int i = c.dequeueOutputBuffer(info, 20000);
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(c.getOutputFormat());
                    muxer.start();
                    muxing = true;
                } else if (i >= 0) {
                    ByteBuffer buf = c.getOutputBuffer(i);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (buf != null && info.size > 0 && !config && muxing) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        muxer.writeSampleData(track, buf, info);
                        written++;
                    }
                    c.releaseOutputBuffer(i, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else if (!active && i == MediaCodec.INFO_TRY_AGAIN_LATER && written == 0) {
                    break;   // se detuvo antes de recibir un solo cuadro
                }
            }
        } catch (Exception ignored) {
            // el codificador se cerro
        }
    }

    private void releaseQuietly() {
        try { if (muxer != null && muxing) muxer.stop(); } catch (Exception ignored) { }
        try { if (muxer != null) muxer.release(); } catch (Exception ignored) { }
        try { if (codec != null) codec.stop(); } catch (Exception ignored) { }
        try { if (codec != null) codec.release(); } catch (Exception ignored) { }
        try { if (surface != null) surface.release(); } catch (Exception ignored) { }
        muxer = null;
        codec = null;
        surface = null;
        muxing = false;
        active = false;
    }
}
