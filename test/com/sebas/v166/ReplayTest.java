package com.sebas.v166;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import javax.imageio.ImageIO;

public class ReplayTest {
    static int frames = 0, decoded = 0;
    static String version = "";
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }

    public static void main(String[] a) throws Exception {
        byte[] all = Files.readAllBytes(new File(a[0]).toPath());
        final File out = new File(a[1]);
        DroneLink link = new DroneLink("127.0.0.1", new DroneLink.Listener() {
            public void onFrame(byte[] jpeg, int w, int h) {
                frames++;
                try {
                    BufferedImage im = ImageIO.read(new ByteArrayInputStream(jpeg));
                    if (im != null && im.getWidth() == w && im.getHeight() == h) decoded++;
                    if (frames == 1) { Files.write(new File(out, "replay_1.jpg").toPath(), jpeg); }
                } catch (Exception e) { System.out.println("decode error " + e); }
            }
            public void onLive(boolean live) { System.out.println("live=" + live); }
            public void onMcuMessage(byte[] p) { version = new String(p, 2, p.length - 2); }
        }, null);
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        int pk = 0;
        while (bb.remaining() >= 12) {
            bb.getDouble(); bb.getShort(); int len = bb.getShort() & 0xFFFF;
            byte[] d = new byte[4096]; bb.get(d, 0, len);
            link.handle(d, len); pk++;
        }
        System.out.println("paquetes=" + pk + " cuadros=" + frames + " decodificados=" + decoded + " perdidos=" + link.getDropped());
        System.out.println("version=" + version);
        // paquetes de referencia capturados de la app original
        String cmdHex = hex(DroneLink.buildCommand(0, 0, 0, 0, 0, 0, false));
        System.out.println("comando neutro ok=" + cmdHex.equals("6614808080800002000000000000000000000299") + " " + cmdHex);
        System.out.println("gas arriba/derecha 100% = " + hex(DroneLink.buildCommand(1, 1, 1, 1, 2, 0x01, true)));
        System.out.println("30% = " + hex(DroneLink.buildCommand(1, -1, -1, 1, 0, 0, false)));
        byte[] ack = link.buildAck(true);
        System.out.println("ack len=" + ack.length + " " + hex(ack));
    }
}
