package com.sebas.v166;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.DatagramSocket;
import java.util.ArrayList;

public class MainActivity extends Activity implements DroneLink.Listener {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private DroneLink link;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCallback;
    private volatile Network wifi;

    // video
    private final Object frameLock = new Object();
    private byte[] pendingJpeg;
    private volatile byte[] lastJpeg;
    private volatile int frameW, frameH;
    private Thread decoder;
    private volatile boolean decoding;
    private final Recorder recorder = new Recorder();
    private long recordStart;

    // vistas
    private FrameLayout root;
    private FrameView video;
    private TextView status, statusDot, recTimer, hint;
    private LinearLayout connectCard, pilotTop, pilotBottom, tiltColumn;
    private JoystickView left, right;
    private HudButton btnPhoto, btnRecord, btnCam, btnPilot, btnSpeed, btnHeadless, btnFlip;
    private View flash;

    private boolean live;
    private boolean pilot;
    private boolean flipArmed;
    private final int[] orient = {1, 0};   // por camara: frontal girada 180 (asi viene montada), inferior normal
    private float lx, ly, rx, ry;
    private String version = "";
    private final ArrayList<String> mcuLog = new ArrayList<String>();

    // ------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        link = new DroneLink(DroneLink.DEFAULT_IP, this, new DroneLink.SocketBinder() {
            @Override
            public void bind(DatagramSocket socket) throws Exception {
                Network n = wifi;
                if (n != null) n.bindSocket(socket);   // obliga a salir por la WiFi del dron, no por datos moviles
            }
        });
        SharedPreferences prefs = getSharedPreferences("v166", MODE_PRIVATE);
        orient[0] = prefs.getInt("orient0", 1);
        orient[1] = prefs.getInt("orient1", 0);
        link.setTiltVariant(0);   // las variantes B, C y D no hicieron nada en el V166
        buildUi();
        applyOrientation();
        setPilot(false);
        applyLive(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        decoding = true;
        decoder = new Thread(new Runnable() {
            @Override public void run() { decodeLoop(); }
        }, "decoder");
        decoder.start();
        link.start();
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                wifi = network;
                if (link.isDemo()) return;
                link.stop();
                link.start();
            }

            @Override
            public void onLost(Network network) {
                if (network.equals(wifi)) wifi = null;
            }
        };
        try {
            cm.requestNetwork(req, netCallback);
        } catch (Exception e) {
            netCallback = null;
        }
        ui.post(ticker);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(ticker);
        if (recorder.isActive()) stopRecording();
        setPilot(false);
        link.stop();
        if (netCallback != null) {
            try { cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) { }
            netCallback = null;
        }
        decoding = false;
        synchronized (frameLock) { frameLock.notifyAll(); }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    // --------------------------------------------------------- eventos del dron

    @Override
    public void onFrame(byte[] jpeg, int w, int h) {
        frameW = w;
        frameH = h;
        lastJpeg = jpeg;
        synchronized (frameLock) {
            pendingJpeg = jpeg;
            frameLock.notifyAll();
        }
    }

    @Override
    public void onLive(final boolean isLive) {
        ui.post(new Runnable() {
            @Override public void run() { applyLive(isLive); }
        });
    }

    @Override
    public void onMcuMessage(final byte[] p) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                StringBuilder hex = new StringBuilder();
                StringBuilder txt = new StringBuilder();
                for (int i = 0; i < p.length && i < 96; i++) {
                    hex.append(String.format("%02x", p[i] & 0xFF));
                    int c = p[i] & 0xFF;
                    txt.append(c >= 32 && c < 127 ? (char) c : '.');
                }
                if (p.length > 2 && p[0] == 1 && p[1] == 101) version = txt.substring(2);
                String line = hex + "  " + txt;
                if (!mcuLog.contains(line)) {
                    mcuLog.add(line);
                    if (mcuLog.size() > 12) mcuLog.remove(0);
                }
            }
        });
    }

    private void decodeLoop() {
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
        while (decoding) {
            byte[] jpeg;
            synchronized (frameLock) {
                while (pendingJpeg == null && decoding) {
                    try { frameLock.wait(500); } catch (InterruptedException e) { return; }
                }
                jpeg = pendingJpeg;
                pendingJpeg = null;
            }
            if (jpeg == null) continue;
            final Bitmap bmp;
            try {
                bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opt);
            } catch (Throwable t) {
                continue;
            }
            if (bmp == null) continue;
            recorder.draw(bmp);
            ui.post(new Runnable() {
                @Override public void run() { video.setFrame(bmp); }
            });
        }
    }

    // ------------------------------------------------------------------ interfaz

    private int dp(float v) { return Ui.dp(this, v); }

    private GradientDrawable panel(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), Ui.PANEL_STROKE);
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return t;
    }

    private FrameLayout.LayoutParams at(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private HudButton button(int icon, String label, LinearLayout parent, int w, int h) {
        HudButton b = new HudButton(this, icon, label);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(w), dp(h));
        int m = dp(4);
        p.setMargins(m, m, m, m);
        parent.addView(b, p);
        return b;
    }

    private void buildUi() {
        final int WRAP = FrameLayout.LayoutParams.WRAP_CONTENT, MATCH = FrameLayout.LayoutParams.MATCH_PARENT;
        root = new FrameLayout(this);
        video = new FrameView(this);
        root.addView(video, new FrameLayout.LayoutParams(MATCH, MATCH));

        // ---- estado (arriba a la izquierda) ----
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(panel(Ui.PANEL, 20));
        pill.setPadding(dp(12), dp(7), dp(14), dp(7));
        statusDot = text("●", 11, Ui.WARN, false);
        status = text("Buscando dron…", 13, Ui.TEXT, true);
        status.setPadding(dp(7), 0, 0, 0);
        recTimer = text("", 13, Ui.DANGER, true);
        recTimer.setPadding(dp(10), 0, 0, 0);
        pill.addView(statusDot);
        pill.addView(status);
        pill.addView(recTimer);
        pill.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showDiagnostics(); return true; }
        });
        root.addView(pill, at(WRAP, WRAP, Gravity.TOP | Gravity.START, dp(16), dp(12), 0, 0));

        // ---- columna derecha: foto, video, cambio de camara ----
        LinearLayout media = new LinearLayout(this);
        media.setOrientation(LinearLayout.VERTICAL);
        btnPhoto = button(HudButton.CAMERA, "Foto", media, 58, 56);
        btnRecord = button(HudButton.RECORD, "Grabar", media, 58, 56);
        btnCam = button(HudButton.SWITCH_CAM, "Cámara", media, 58, 56);
        root.addView(media, at(WRAP, WRAP, Gravity.END | Gravity.TOP, 0, dp(8), dp(12), 0));
        btnPhoto.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { takePhoto(); }
        });
        btnRecord.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (recorder.isActive()) stopRecording(); else startRecording(); }
        });
        btnCam.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = link.getCameraIndex() == 0 ? 1 : 0;
                link.setCameraIndex(next);
                applyOrientation();
                btnCam.setActive(next == 1);
                toast(next == 1 ? "Cámara inferior" : "Cámara frontal");
            }
        });

        // ---- arriba al centro: ajustes de vuelo (solo en modo piloto) ----
        pilotTop = new LinearLayout(this);
        pilotTop.setOrientation(LinearLayout.HORIZONTAL);
        btnSpeed = button(HudButton.TEXT, "Velocidad", pilotTop, 60, 54);
        btnSpeed.setBigText("30%");
        btnHeadless = button(HudButton.HEADLESS, "Sin cabeza", pilotTop, 60, 54);
        HudButton btnCalib = button(HudButton.CALIBRATE, "Calibrar", pilotTop, 60, 54);
        btnFlip = button(HudButton.FLIP, "Giro 360°", pilotTop, 60, 54);
        root.addView(pilotTop, at(WRAP, WRAP, Gravity.TOP | Gravity.END, 0, dp(8), dp(142), 0));
        btnSpeed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = (link.getSpeedIndex() + 1) % 3;
                link.setSpeedIndex(next);
                btnSpeed.setBigText(next == 0 ? "30%" : next == 1 ? "60%" : "100%");
                btnSpeed.setActive(next == 2);
                btnSpeed.setTint(Ui.WARN);
            }
        });
        btnHeadless.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !btnHeadless.isActive();
                btnHeadless.setActive(on);
                link.setHeadless(on);
            }
        });
        btnCalib.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                link.calibrate();
                toast("Calibrando giroscopio: deja el dron quieto en una superficie plana");
            }
        });
        btnFlip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                flipArmed = !flipArmed;
                btnFlip.setActive(flipArmed);
                if (flipArmed) {
                    toast("Giro armado: empuja la palanca derecha hacia donde quieras girar");
                    ui.removeCallbacks(disarmFlip);
                    ui.postDelayed(disarmFlip, 4000);
                }
            }
        });

        // ---- inclinacion de la camara y giro de imagen (siempre visibles) ----
        tiltColumn = new LinearLayout(this);
        tiltColumn.setOrientation(LinearLayout.VERTICAL);
        HudButton tiltUp = button(HudButton.UP, "", tiltColumn, 50, 44);
        HudButton tiltDown = button(HudButton.DOWN, "", tiltColumn, 50, 44);
        HudButton rotate = button(HudButton.FLIP, "Girar", tiltColumn, 50, 50);
        rotate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int cam = link.getCameraIndex();
                orient[cam] = (orient[cam] + 1) & 3;
                getSharedPreferences("v166", MODE_PRIVATE).edit().putInt("orient" + cam, orient[cam]).apply();
                applyOrientation();
                toast(Orient.NAMES[orient[cam]]);
            }
        });
        root.addView(tiltColumn, at(WRAP, WRAP, Gravity.END | Gravity.TOP, 0, dp(8), dp(80), 0));
        tiltUp.setOnHold(new HudButton.OnHold() {
            @Override public void onHold(boolean down) { if (live) link.setTilt(down ? 2 : 0); }
        });
        tiltDown.setOnHold(new HudButton.OnHold() {
            @Override public void onHold(boolean down) { if (live) link.setTilt(down ? 1 : 0); }
        });

        // ---- palancas ----
        left = new JoystickView(this, "ALTURA · GIRO");
        right = new JoystickView(this, "AVANCE · LADO");
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float shortSideDp = Math.min(dm.widthPixels, dm.heightPixels) / dm.density;
        int stick = dp(Math.max(130f, Math.min(190f, shortSideDp - 206f)));
        root.addView(left, at(stick, stick, Gravity.BOTTOM | Gravity.START, dp(28), 0, 0, dp(14)));
        root.addView(right, at(stick, stick, Gravity.BOTTOM | Gravity.END, 0, 0, dp(28), dp(14)));
        left.setOnMove(new JoystickView.OnMove() {
            @Override public void onMove(float x, float y) { lx = x; ly = y; pushSticks(); }
        });
        right.setOnMove(new JoystickView.OnMove() {
            @Override
            public void onMove(float x, float y) {
                rx = x;
                ry = y;
                pushSticks();
                if (flipArmed && (Math.abs(x) > 0.7f || Math.abs(y) > 0.7f)) {
                    link.flip();
                    flipArmed = false;
                    btnFlip.setActive(false);
                }
            }
        });

        // ---- abajo al centro ----
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.BOTTOM);
        pilotBottom = new LinearLayout(this);
        pilotBottom.setOrientation(LinearLayout.HORIZONTAL);
        HudButton btnTakeoff = button(HudButton.TAKEOFF, "Despegar · Aterrizar", pilotBottom, 132, 60);
        HudButton btnStop = button(HudButton.STOP, "Apagar motores", pilotBottom, 104, 60);
        bottom.addView(pilotBottom);
        btnPilot = button(HudButton.PILOT, "Pilotar", bottom, 78, 60);
        root.addView(bottom, at(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, dp(10)));
        btnTakeoff.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { link.takeoffOrLand(); }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                link.emergencyStop();
                toast("Parada de emergencia enviada");
            }
        });
        btnPilot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pilot) setPilot(false);
                else confirmPilot();
            }
        });

        // ---- tarjeta de conexion ----
        connectCard = new LinearLayout(this);
        connectCard.setOrientation(LinearLayout.VERTICAL);
        connectCard.setGravity(Gravity.CENTER_HORIZONTAL);
        connectCard.setBackground(panel(0xE6101620, 22));
        connectCard.setPadding(dp(26), dp(22), dp(26), dp(20));
        TextView title = text("Conecta el dron", 20, Ui.TEXT, true);
        TextView body = text("1. Enciende el dron.\n2. Conecta el celular a la red WiFi que empieza por FLOW_\n"
                + "3. Si el teléfono avisa que la red no tiene internet, elige mantener la conexión.", 13.5f, Ui.TEXT_DIM, false);
        body.setPadding(0, dp(10), 0, dp(16));
        body.setLineSpacing(dp(3), 1f);
        TextView open = text("Abrir ajustes de WiFi", 14, 0xFF07090D, true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Ui.ACCENT);
        g.setCornerRadius(dp(22));
        open.setBackground(g);
        open.setPadding(dp(22), dp(11), dp(22), dp(11));
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                } catch (Exception e) {
                    toast("Abre los ajustes de WiFi del teléfono");
                }
            }
        });
        connectCard.addView(title);
        connectCard.addView(body);
        connectCard.addView(open, new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView demoBtn = text("Probar sin dron (demostración)", 12.5f, Ui.ACCENT, false);
        demoBtn.setPadding(dp(12), dp(14), dp(12), dp(2));
        demoBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startDemo(); }
        });
        connectCard.addView(demoBtn, new LinearLayout.LayoutParams(WRAP, WRAP));
        root.addView(connectCard, at(dp(380), WRAP, Gravity.CENTER, 0, 0, 0, 0));

        // ---- aviso inferior izquierdo ----
        hint = text("", 11.5f, Ui.TEXT, false);
        hint.setShadowLayer(dp(3), 0, dp(1), 0xFF000000);
        root.addView(hint, at(WRAP, WRAP, Gravity.BOTTOM | Gravity.START, dp(18), 0, 0, dp(12)));

        // ---- destello al tomar foto ----
        flash = new View(this);
        flash.setBackgroundColor(0xFFFFFFFF);
        flash.setAlpha(0f);
        flash.setClickable(false);
        root.addView(flash, new FrameLayout.LayoutParams(MATCH, MATCH));

        setContentView(root);
    }

    private final Runnable disarmFlip = new Runnable() {
        @Override
        public void run() {
            flipArmed = false;
            btnFlip.setActive(false);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (live) {
                String s = link.isDemo() ? "Demostración" : "En vivo";
                if (frameW > 0 && !pilot) s += " · " + frameW + "×" + frameH;
                s += " · " + link.getFps() + " fps";
                if (video.getZoom() > 1.05f) s += String.format(" · zoom %.1f×", video.getZoom());
                status.setText(s);
            }
            if (recorder.isActive()) {
                long sec = (SystemClock.elapsedRealtime() - recordStart) / 1000;
                recTimer.setText(String.format("● REC %02d:%02d", sec / 60, sec % 60));
            } else {
                recTimer.setText("");
            }
            ui.postDelayed(this, 500);
        }
    };

    private void applyLive(boolean isLive) {
        live = isLive;
        statusDot.setTextColor(isLive ? Ui.OK : Ui.WARN);
        if (!isLive) {
            status.setText("Buscando dron…");
            video.setFrame(null);
            if (pilot) setPilot(false);
            if (recorder.isActive()) stopRecording();
        }
        connectCard.setVisibility(isLive ? View.GONE : View.VISIBLE);
        btnPhoto.setEnabled(isLive);
        btnRecord.setEnabled(isLive);
        btnCam.setEnabled(isLive);
        btnPilot.setEnabled(isLive);
        btnPhoto.invalidate();
        btnRecord.invalidate();
        btnCam.invalidate();
        btnPilot.invalidate();
        updateHint();
    }

    private void updateHint() {
        if (!live) hint.setText("");
        else if (pilot) hint.setText("");
        else hint.setText("Modo cámara: vuela con el control remoto. ⌃ ⌄ inclinan la cámara, «Girar» voltea la imagen.");
    }

    // ------------------------------------------------------------- modo piloto

    private void confirmPilot() {
        SharedPreferences prefs = getSharedPreferences("v166", MODE_PRIVATE);
        if (prefs.getBoolean("pilot_ok", false)) {
            setPilot(true);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Pilotar desde el celular")
                .setMessage("El celular empezará a enviar comandos de vuelo al dron.\n\n"
                        + "• Ten el control remoto a la mano por si acaso.\n"
                        + "• Empieza en velocidad 30 % y en un lugar despejado.\n"
                        + "• «Apagar motores» los detiene al instante: el dron cae.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Entendido", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        getSharedPreferences("v166", MODE_PRIVATE).edit().putBoolean("pilot_ok", true).apply();
                        setPilot(true);
                    }
                })
                .show();
    }

    private void setPilot(boolean on) {
        pilot = on && live;
        int v = pilot ? View.VISIBLE : View.GONE;
        pilotTop.setVisibility(v);
        pilotBottom.setVisibility(v);
        left.setVisibility(v);
        right.setVisibility(v);
        left.reset();
        right.reset();
        lx = ly = rx = ry = 0;
        flipArmed = false;
        btnFlip.setActive(false);
        btnPilot.setActive(pilot);
        btnPilot.setLabel(pilot ? "Soltar" : "Pilotar");
        link.setControl(pilot);
        updateHint();
    }

    private void applyOrientation() {
        int o = orient[link.getCameraIndex()];
        video.setOrientation(o);
        recorder.setOrientation(o);
    }

    private void pushSticks() {
        if (pilot) link.setSticks(lx, ly, rx, ry);
    }

    // ----------------------------------------------------------- foto y video

    private void takePhoto() {
        final byte[] jpeg = lastJpeg;
        if (jpeg == null) {
            toast("Todavía no hay imagen");
            return;
        }
        flash.setAlpha(0.7f);
        flash.animate().alpha(0f).setDuration(260).start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String where = MediaSaver.savePhoto(MainActivity.this, Orient.apply(jpeg, orient[link.getCameraIndex()]));
                ui.post(new Runnable() {
                    @Override
                    public void run() { toast(where != null ? "Foto guardada en " + where : "No se pudo guardar la foto"); }
                });
            }
        }).start();
    }

    private void startRecording() {
        if (frameW <= 0) {
            toast("Todavía no hay imagen");
            return;
        }
        File f = MediaSaver.tempVideo(this);
        if (recorder.start(f, frameW, frameH)) {
            recordStart = SystemClock.elapsedRealtime();
            btnRecord.setActive(true);
            btnRecord.setLabel("Detener");
        } else {
            toast("No se pudo iniciar la grabación");
        }
    }

    private void stopRecording() {
        btnRecord.setActive(false);
        btnRecord.setLabel("Grabar");
        new Thread(new Runnable() {
            @Override
            public void run() {
                File f = recorder.stop();
                final String where = MediaSaver.publishVideo(MainActivity.this, f);
                ui.post(new Runnable() {
                    @Override
                    public void run() { toast(where != null ? "Video guardado en " + where : "La grabación quedó vacía"); }
                });
            }
        }).start();
    }

    // -------------------------------------------------------------- utilidades

    private void startDemo() {
        try {
            java.io.InputStream in = getAssets().open("demo.bin");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            link.startDemo(out.toByteArray());
            toast("Demostración: video grabado, los controles no envían nada");
        } catch (Exception e) {
            toast("No se pudo abrir la demostración");
        }
    }

    private void showDiagnostics() {
        StringBuilder s = new StringBuilder();
        s.append("Firmware: ").append(version.length() > 0 ? version : "—").append('\n');
        s.append("Paquetes: ").append(link.getPackets()).append("   Cuadros: ").append(link.getFrames())
                .append("   Perdidos: ").append(link.getDropped()).append('\n');
        s.append("Modo de respaldo: ").append(link.isCompat() ? "sí" : "no").append('\n');
        s.append("WiFi atada: ").append(wifi != null ? "sí" : "no").append("\n\nMensajes del dron:\n");
        for (String m : mcuLog) s.append(m).append('\n');
        s.append("\nVelocidad: nivel ").append(link.getSpeedIndex() + 1).append(" de 3");
        new AlertDialog.Builder(this).setTitle("Diagnóstico").setMessage(s.toString())
                .setPositiveButton("Cerrar", null).show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
