package com.sebas.v166;

/**
 * El dron envia solo los datos comprimidos de cada cuadro. La cabecera JPEG
 * (tablas de cuantizacion, dimensiones y tablas Huffman estandar) la pone el cliente.
 */
final class JpegHeader {
    private static final int[] ZIGZAG = {
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21,
        28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61,
        54, 47, 55, 62, 63};
    private static final int[] LUMA = {
        16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99};
    private static final int[] CHROMA = {
        17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99,
        47, 66, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99};
    /** Tablas Huffman estandar (anexo K de la norma JPEG), segmentos DHT completos. */
    private static final String DHT_HEX = "ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9fa";

    private JpegHeader() {}

    static byte[] build(int width, int height, int quality) {
        byte[] dht = unhex(DHT_HEX);
        byte[] out = new byte[2 + 69 + 69 + 19 + dht.length + 14];
        int p = 0;
        out[p++] = (byte) 0xFF; out[p++] = (byte) 0xD8;
        p = dqt(out, p, 0, LUMA, quality);
        p = dqt(out, p, 1, CHROMA, quality);
        // SOF0, 3 componentes, muestreo 4:4:4
        out[p++] = (byte) 0xFF; out[p++] = (byte) 0xC0; out[p++] = 0; out[p++] = 17; out[p++] = 8;
        out[p++] = (byte) (height >> 8); out[p++] = (byte) height;
        out[p++] = (byte) (width >> 8); out[p++] = (byte) width;
        out[p++] = 3;
        out[p++] = 1; out[p++] = 0x11; out[p++] = 0;
        out[p++] = 2; out[p++] = 0x11; out[p++] = 1;
        out[p++] = 3; out[p++] = 0x11; out[p++] = 1;
        System.arraycopy(dht, 0, out, p, dht.length); p += dht.length;
        // SOS
        out[p++] = (byte) 0xFF; out[p++] = (byte) 0xDA; out[p++] = 0; out[p++] = 12; out[p++] = 3;
        out[p++] = 1; out[p++] = 0x00; out[p++] = 2; out[p++] = 0x11; out[p++] = 3; out[p++] = 0x11;
        out[p++] = 0; out[p++] = 63; out[p++] = 0;
        return out;
    }

    private static int dqt(byte[] out, int p, int id, int[] base, int quality) {
        int q = Math.max(1, Math.min(100, quality <= 0 ? 50 : quality));
        int scale = q < 50 ? 5000 / q : 200 - 2 * q;
        out[p++] = (byte) 0xFF; out[p++] = (byte) 0xDB; out[p++] = 0; out[p++] = 67; out[p++] = (byte) id;
        for (int i = 0; i < 64; i++) {
            int v = (base[ZIGZAG[i]] * scale + 50) / 100;
            out[p++] = (byte) Math.max(1, Math.min(255, v));
        }
        return p;
    }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }
}
