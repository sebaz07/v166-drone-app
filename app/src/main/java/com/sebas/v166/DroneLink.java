package com.sebas.v166;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

/**
 * Enlace con el dron V166 (familia "WiFi UAV", redes FLOW_xxxxxx).
 *
 * Todo viaja por UDP hacia 192.168.169.1:8800 (y 8801 en algunas placas):
 *   - ef 00 04 00               -> pide iniciar el video
 *   - ef 02 ...                 -> confirmacion de cuadros + comando de vuelo incrustado
 *   - 93 01 ... (desde el dron) -> fragmento de un cuadro JPEG sin cabecera
 *   - 93 04 ... (desde el dron) -> mensaje del microcontrolador (version, estado)
 *
 * Protocolo documentado por el proyecto abierto turbodrone (Apache-2.0).
 * Esta clase no depende de Android para poder probarla en un PC.
 */
final class DroneLink implements Runnable {

    interface Listener {
        /** Cuadro JPEG completo. El arreglo es nuevo en cada llamada. */
        void onFrame(byte[] jpeg, int width, int height);
        void onLive(boolean live);
        void onMcuMessage(byte[] payload);
    }

    /** Permite atar el socket a la red WiFi del dron en Android. */
    interface SocketBinder {
        void bind(DatagramSocket socket) throws Exception;
    }

    static final String DEFAULT_IP = "192.168.169.1";
    private static final int[] PORTS = {8800, 8801};
    private static final byte[] START = {(byte) 0xEF, 0x00, 0x04, 0x00};
    private static final byte[] ASK_VERSION = {(byte) 0xEF, 0x20, 0x06, 0x00, 0x01, 0x65};
    private static final float[] SPEED_SCALE = {0.30f, 0.60f, 1.00f};
    /** La altura usa siempre todo el recorrido (como la app original): con menos, este dron no sube. */
    private static final float[] THROTTLE_SCALE = {1.00f, 1.00f, 1.00f};
    static final String[] TILT_VARIANTS = {
        "A: byte 6, bits 6-7 (documentada)", "B: byte 6, bits 4-5", "C: byte 8 = 1/2", "D: byte 7, bits 4-5"};

    static final int FLAG_TAKEOFF_LAND = 0x01;
    static final int FLAG_STOP = 0x02;
    static final int FLAG_CALIBRATE = 0x04;
    static final int FLAG_FLIP = 0x08;

    private final String ip;
    private final Listener listener;
    private final SocketBinder binder;
    private volatile boolean running;
    private volatile Thread thread;
    private DatagramSocket socket;
    private InetAddress address;

    // ---- estado de control (lo escribe la interfaz, lo lee el hilo de red) ----
    private volatile boolean control;
    private volatile float yaw, pitch, throttle, roll;   // -1..1
    private volatile int speedIndex = 0;
    private volatile boolean headless;
    private volatile int tilt;                            // 0 neutro, 1 / 2 direcciones opuestas
    private volatile int cameraIndex;
    private volatile int tiltVariant;
    private volatile int oneShot;                         // banderas que viajan en un solo comando
    private volatile long stopUntil;
    private volatile boolean releasePending;
    private volatile boolean demo;

    // ---- estado del hilo de red ----
    private volatile boolean live;
    private long lastRx, lastTx, lastCmdTx, lastFrameAt, firstRxAt;
    private long lastSeq = -1;
    private int cmdSeq;
    private int holdTicks;
    private float sYaw, sPitch, sThrottle, sRoll;   // palancas suavizadas
    private byte[] cmd = new byte[0];
    private boolean compat;                 // modo de respaldo: igual al script de diagnostico
    private final Frame[] slots = new Frame[4];
    private byte[] header;
    private int headerW, headerH, headerQ;

    // ---- estadisticas ----
    private volatile int fps;
    private volatile long packets, frames, dropped;
    private int fpsCount;
    private long fpsMark;

    private static final class Frame {
        long seq = -1;
        int total, got, width, height, quality;
        byte[][] parts;
    }

    DroneLink(String ip, Listener listener, SocketBinder binder) {
        this.ip = ip;
        this.listener = listener;
        this.binder = binder;
        for (int i = 0; i < slots.length; i++) slots[i] = new Frame();
    }

    // ------------------------------------------------------------------ API

    synchronized void start() {
        if (running) return;
        running = true;
        demo = false;
        thread = new Thread(this, "drone-link");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        DatagramSocket s = socket;
        if (s != null) s.close();
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(300); } catch (InterruptedException ignored) { }
        }
        if (live) {
            live = false;
            listener.onLive(false);
        }
    }

    /** Activa o desactiva el envio de comandos de vuelo desde el celular. */
    void setControl(boolean enabled) {
        if (control && !enabled) releasePending = true;
        if (!enabled) {
            yaw = pitch = throttle = roll = 0f;
            tilt = 0;
            oneShot = 0;
            stopUntil = 0;
        }
        control = enabled;
    }

    boolean isControl() { return control; }

    /**
     * Modo demostracion: reproduce en bucle una captura real del dron (sin red).
     * Formato de cada registro: double tiempo, u16 puerto, u16 largo, datos.
     */
    synchronized void startDemo(final byte[] capture) {
        stop();
        running = true;
        demo = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { demoLoop(capture); }
        }, "drone-demo");
        thread.start();
    }

    boolean isDemo() { return demo; }

    private void demoLoop(byte[] cap) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(cap).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        byte[] d = new byte[4096];
        resetSession();
        double last = -1;
        try {
            while (alive()) {
                if (bb.remaining() < 12) {
                    bb.position(0);
                    last = -1;
                    lastSeq = -1;
                    for (Frame f : slots) { f.seq = -1; f.parts = null; }
                    continue;
                }
                double ts = bb.getDouble();
                bb.getShort();
                int len = bb.getShort() & 0xFFFF;
                if (len > d.length || len > bb.remaining()) { bb.position(bb.limit()); continue; }
                bb.get(d, 0, len);
                if (last >= 0 && ts > last) {
                    long ms = Math.min(200, Math.round((ts - last) * 1000));
                    if (ms > 0) Thread.sleep(ms);
                }
                last = ts;
                long t = now();
                if (t - fpsMark >= 1000) { fps = fpsCount; fpsCount = 0; fpsMark = t; }
                handle(d, len);
            }
        } catch (Exception ignored) {
            // demo interrumpida
        }
    }

    void setSticks(float yaw, float throttle, float roll, float pitch) {
        this.yaw = clamp(yaw);
        this.throttle = clamp(throttle);
        this.roll = clamp(roll);
        this.pitch = clamp(pitch);
    }

    void setSpeedIndex(int index) { speedIndex = Math.max(0, Math.min(2, index)); }
    int getSpeedIndex() { return speedIndex; }
    void setHeadless(boolean on) { headless = on; }
    /** Inclinacion de la camara. Fuera del modo piloto se envia sola, con las palancas en neutro. */
    void setTilt(int state) {
        int next = Math.max(0, Math.min(2, state));
        if (!control && next == 0 && tilt != 0) releasePending = true;
        tilt = next;
    }
    void setCameraIndex(int index) { cameraIndex = index & 1; }
    void setTiltVariant(int v) { tiltVariant = ((v % TILT_VARIANTS.length) + TILT_VARIANTS.length) % TILT_VARIANTS.length; }
    int getTiltVariant() { return tiltVariant; }
    int getCameraIndex() { return cameraIndex; }

    /** Despegue y aterrizaje comparten la misma orden (la app original hace lo mismo). */
    void takeoffOrLand() { if (control) oneShot |= FLAG_TAKEOFF_LAND; }
    /** Corta motores. Se repite durante 600 ms para que no se pierda. */
    void emergencyStop() { if (control) stopUntil = now() + 600; }
    void calibrate() { if (control) oneShot |= FLAG_CALIBRATE; }
    void flip() { if (control) oneShot |= FLAG_FLIP; }

    int getFps() { return fps; }
    long getPackets() { return packets; }
    long getFrames() { return frames; }
    long getDropped() { return dropped; }
    boolean isCompat() { return compat; }

    // ------------------------------------------------------------ hilo de red

    /** Falso cuando este hilo fue reemplazado por otro (reinicio del enlace). */
    private boolean alive() { return running && Thread.currentThread() == thread; }

    @Override
    public void run() {
        byte[] buf = new byte[4096];
        DatagramPacket in = new DatagramPacket(buf, buf.length);
        while (alive()) {
            DatagramSocket mine = null;
            try {
                address = InetAddress.getByName(ip);
                DatagramSocket s = new DatagramSocket();
                s.setReceiveBufferSize(1 << 20);
                s.setSoTimeout(15);
                if (binder != null) binder.bind(s);
                mine = s;
                socket = s;
                resetSession();
                while (alive()) {
                    tick();
                    in.setLength(buf.length);
                    try {
                        s.receive(in);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    handle(buf, in.getLength());
                }
            } catch (Exception e) {
                // red caida, socket cerrado, etc.: reintenta
            } finally {
                if (mine != null) mine.close();
                if (socket == mine) socket = null;
                if (live && alive()) { live = false; listener.onLive(false); }
            }
            if (alive()) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            }
        }
    }

    private void resetSession() {
        live = false;
        lastSeq = -1;
        lastRx = lastTx = lastCmdTx = lastFrameAt = firstRxAt = 0;
        for (Frame f : slots) { f.seq = -1; f.parts = null; }
        fps = 0; fpsCount = 0; fpsMark = now();
    }

    private void tick() throws Exception {
        long t = now();
        if (t - fpsMark >= 1000) {
            fps = fpsCount; fpsCount = 0; fpsMark = t;
        }
        if (!live) {
            if (t - lastTx >= 100) {
                send(START);
                send(buildAck(false));
                lastTx = t;
            }
            return;
        }
        if (t - lastRx > 3000) {
            live = false;
            lastSeq = -1;
            listener.onLive(false);
            return;
        }
        // Si llegan paquetes pero nunca se completa un cuadro, usa el modo de respaldo.
        if (!compat && !control && t - firstRxAt > 2500 && t - lastFrameAt > 2500) {
            compat = true;
        }
        boolean needCmd = control || releasePending || tilt != 0;
        if (needCmd && t - lastCmdTx >= 30) {
            refreshCommand(t);
            send(buildAck(true));
            lastCmdTx = t;
            lastTx = t;
        } else if (t - lastRx > 300 && t - lastTx > 200) {
            send(START);
            send(buildAck(true));
            lastTx = t;
        }
    }

    void handle(byte[] d, int len) throws Exception {
        if (len < 12 || (d[0] & 0xFF) != 0x93) return;
        long t = now();
        packets++;
        lastRx = t;
        if (!live) {
            live = true;
            firstRxAt = t;
            lastFrameAt = t;
            listener.onLive(true);
            send(ASK_VERSION);
        }
        int type = d[1] & 0xFF;
        if (type == 0x04) {
            int n = u16(d, 8);
            if (n > 0 && 12 + n <= len) {
                byte[] payload = new byte[n];
                System.arraycopy(d, 12, payload, 0, n);
                listener.onMcuMessage(payload);
            }
            return;
        }
        if (type != 0x01 || len < 56 || u16(d, 2) != len) return;

        long seq = u64(d, 8);
        int frag = (int) u32(d, 32);
        int total = (int) u32(d, 36);
        if (total <= 0 || total > 4096 || frag < 0 || frag >= total) return;
        if (seq <= lastSeq && lastSeq - seq < 64) return; // cuadro ya entregado o viejo

        Frame f = slots[(int) (seq & 3)];
        if (f.seq != seq || f.parts == null || f.total != total) {
            if (f.parts != null && f.seq != seq && f.got < f.total) dropped++;
            f.seq = seq;
            f.total = total;
            f.got = 0;
            f.parts = new byte[total][];
            f.width = u16(d, 44);
            f.height = u16(d, 46);
            f.quality = d[48] & 0xFF;
        }
        if (f.parts[frag] == null) {
            byte[] part = new byte[len - 56];
            System.arraycopy(d, 56, part, 0, part.length);
            f.parts[frag] = part;
            f.got++;
        }
        if (f.got < f.total) return;

        // ---- cuadro completo ----
        if (header == null || headerW != f.width || headerH != f.height || headerQ != f.quality) {
            header = JpegHeader.build(f.width, f.height, f.quality);
            headerW = f.width; headerH = f.height; headerQ = f.quality;
        }
        int size = header.length + 2;
        for (byte[] p : f.parts) size += p.length;
        byte[] jpeg = new byte[size];
        System.arraycopy(header, 0, jpeg, 0, header.length);
        int pos = header.length;
        for (byte[] p : f.parts) {
            System.arraycopy(p, 0, jpeg, pos, p.length);
            pos += p.length;
        }
        jpeg[pos] = (byte) 0xFF;
        jpeg[pos + 1] = (byte) 0xD9;
        int w = f.width, h = f.height;
        f.parts = null;
        f.seq = -1;

        lastSeq = seq;
        lastFrameAt = t;
        frames++;
        fpsCount++;
        // pide el siguiente cuadro (misma secuencia que la app original)
        send(buildAck(false));
        send(buildAck(true));
        lastTx = t;
        listener.onFrame(jpeg, w, h);
    }

    // --------------------------------------------------------------- paquetes

    /** Comando extendido de 20 bytes: 66 14 yaw pitch gas roll flags modo ... xor 99 */
    static byte[] buildCommand(float yaw, float pitch, float throttle, float roll,
                               int speedIndex, int flags, boolean headless) {
        return buildCommand(yaw, pitch, throttle, roll, speedIndex, flags, headless, 0, 0);
    }

    static byte[] buildCommand(float yaw, float pitch, float throttle, float roll,
                               int speedIndex, int flags, boolean headless, int tilt, int tiltVariant) {
        int si = Math.max(0, Math.min(2, speedIndex));
        float s = SPEED_SCALE[si];
        byte[] c = new byte[20];
        c[0] = 0x66;
        c[1] = 0x14;
        c[2] = axis(yaw, s);
        c[3] = axis(pitch, s);
        c[4] = axis(throttle, THROTTLE_SCALE[si]);
        c[5] = axis(roll, s);
        c[6] = (byte) flags;
        c[7] = (byte) (0x02 | (headless ? 0x01 : 0x00));
        int tl = tilt & 3;
        if (tl != 0) {
            switch (tiltVariant) {
                case 1: c[6] |= (byte) (tl << 4); break;
                case 2: c[8] = (byte) tl; break;
                case 3: c[7] |= (byte) (tl << 4); break;
                default: c[6] |= (byte) (tl << 6); break;
            }
        }
        int x = 0;
        for (int i = 2; i <= 17; i++) x ^= c[i] & 0xFF;
        c[18] = (byte) x;
        c[19] = (byte) 0x99;
        return c;
    }

    /** Curva suave: poco recorrido = movimiento fino; a fondo sigue llegando al maximo. */
    static float expo(float v) { return v * (0.4f + 0.6f * v * v); }

    /** Limita el cambio por envio (~30 ms): acelera gradual, frena mas rapido. */
    private static float smooth(float current, float target) {
        boolean braking = Math.abs(target) < Math.abs(current) || target * current < 0;
        float step = braking ? 0.30f : 0.12f;
        float d = target - current;
        if (d > step) d = step;
        if (d < -step) d = -step;
        return current + d;
    }

    private static byte axis(float v, float scale) {
        int b = Math.round(128f + clamp(v) * scale * 127f);
        return (byte) Math.max(0, Math.min(255, b));
    }

    private void refreshCommand(long t) {
        if (!control && tilt != 0) {
            cmd = buildCommand(0, 0, 0, 0, speedIndex, 0, false, tilt, tiltVariant);
            cmdSeq++;
            return;
        }
        if (!control) {
            sYaw = sPitch = sThrottle = sRoll = 0f;
            // al soltar el control la app original manda una vez 8 bytes en cero
            cmd = new byte[8];
            cmdSeq++;
            releasePending = false;
            return;
        }
        boolean stop = t < stopUntil;
        if (holdTicks > 0 && !stop) {
            // repite el mismo comando (misma secuencia) para que una orden de un solo
            // disparo sobreviva a un paquete perdido sin ejecutarse dos veces
            holdTicks--;
            return;
        }
        int flags = oneShot;
        oneShot = 0;
        holdTicks = flags != 0 ? 2 : 0;
        if (stop) flags |= FLAG_STOP;
        sYaw = smooth(sYaw, expo(yaw));
        sPitch = smooth(sPitch, expo(pitch));
        sThrottle = smooth(sThrottle, expo(throttle));
        sRoll = smooth(sRoll, expo(roll));
        cmd = buildCommand(sYaw, sPitch, sThrottle, sRoll, speedIndex, flags, headless, tilt, tiltVariant);
        cmdSeq++;
    }

    /** ef 02 <len> 02 02 00 01 <n> 000000 <seqCmd> <lenCmd> <cmd[64]> <calidad[4]> <camara> 00 <slots> */
    byte[] buildAck(boolean withSlots) {
        boolean slotsOn = withSlots && lastSeq >= 0;
        byte[] c = cmd;
        int seqField = cmdSeq;
        if (compat && !control && !releasePending && tilt == 0) {
            c = buildCommand(0, 0, 0, 0, 2, 0, false);
            seqField = (int) (lastSeq + 1);
        }
        byte[] p = new byte[88 + (slotsOn ? 36 : 0)];
        p[0] = (byte) 0xEF; p[1] = 0x02;
        put16(p, 2, p.length);
        p[4] = 0x02; p[5] = 0x02; p[6] = 0x00; p[7] = 0x01;
        p[8] = (byte) (slotsOn ? 2 : 0);
        put32(p, 12, seqField);
        put16(p, 16, c.length);
        System.arraycopy(c, 0, p, 18, c.length);
        p[82] = 0x32; p[83] = 0x4B; p[84] = 0x14; p[85] = 0x2D;
        p[86] = (byte) cameraIndex;
        if (slotsOn) {
            // cuadro lastSeq recibido completo
            put64(p, 88, lastSeq);
            put32(p, 96, 1);
            put32(p, 100, 20);
            p[104] = p[105] = p[106] = p[107] = (byte) 0xFF;
            // se espera el siguiente
            put64(p, 108, lastSeq + 1);
            put32(p, 116, 3);
            put32(p, 120, 16);
        }
        return p;
    }

    private void send(byte[] data) throws Exception {
        DatagramSocket s = socket;
        if (s == null) return;
        for (int port : PORTS) {
            s.send(new DatagramPacket(data, data.length, address, port));
        }
    }

    // ----------------------------------------------------------------- utiles

    private static long now() { return System.nanoTime() / 1000000L; }
    private static float clamp(float v) { return v < -1f ? -1f : (v > 1f ? 1f : v); }
    private static int u16(byte[] d, int o) { return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8); }
    private static long u32(byte[] d, int o) {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8) | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }
    private static long u64(byte[] d, int o) { return u32(d, o) | (u32(d, o + 4) << 32); }
    private static void put16(byte[] d, int o, int v) { d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); }
    private static void put32(byte[] d, int o, long v) {
        d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); d[o + 2] = (byte) (v >> 16); d[o + 3] = (byte) (v >> 24);
    }
    private static void put64(byte[] d, int o, long v) { put32(d, o, v); put32(d, o + 4, v >> 32); }
}
