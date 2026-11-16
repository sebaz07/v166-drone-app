#!/usr/bin/env python3
"""
Diagnostico del dron V166 (red WiFi FLOW_xxxxxx).

Solo usa la libreria estandar de Python 3.8+. No instala nada.

Uso:
  1. Enciende el dron y dejalo quieto sobre una mesa (NO hace falta el control).
  2. Conecta el PC a la red WiFi FLOW_D2D066.
  3. Ejecuta:   python diagnostico_v166.py
  4. Vuelve a tu WiFi normal y comparte la carpeta "diagnostico_v166_out".

El script NO envia ordenes de despegue: solo pide video y manda el paquete
"sticks en neutro" que la app original manda todo el tiempo.

Protocolo basado en la investigacion abierta de turbodrone
(github.com/marshallrichards/turbodrone, licencia Apache-2.0).
"""
import argparse
import os
import select
import socket
import struct
import sys
import time
from concurrent.futures import ThreadPoolExecutor

START_STREAM = b"\xef\x00\x04\x00"
ASK_VERSION = b"\xef\x20\x06\x00\x01\x65"
NEUTRAL_CMD = (b"\x66\x14\x80\x80\x80\x80\x00\x02" + b"\x00" * 10 + b"\x02\x99")
QUALITY = b"\x32\x4b\x14\x2d\x00"
UDP_PORTS = (8800, 8801)
TCP_PORTS = (21, 22, 23, 80, 443, 554, 1234, 7060, 7070, 8060, 8080, 8081,
             8554, 8800, 8801, 8888, 9000, 9060, 10010, 10011, 18881, 40000)

LUMA_QT = [16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
           14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
           18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
           49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99]
CHROMA_QT = [17, 18, 24, 47] + [99] * 4 + [18, 21, 26, 66] + [99] * 4 + \
            [24, 26, 56] + [99] * 5 + [47, 66] + [99] * 38

LOG = []


def log(msg=""):
    print(msg, flush=True)
    LOG.append(msg)


def ack_slot(seq, status, bitmap=b""):
    return struct.pack("<QII", seq, status, 16 + len(bitmap)) + bitmap


def ack_packet(cmd_seq, slots):
    p = bytearray(b"\xef\x02\x00\x00\x02\x02\x00\x01")
    p += bytes([len(slots)]) + b"\x00\x00\x00"
    p += struct.pack("<IH", cmd_seq & 0xFFFFFFFF, len(NEUTRAL_CMD))
    p += NEUTRAL_CMD.ljust(64, b"\x00") + QUALITY + b"\x00"
    for s in slots:
        p += s
    p[2:4] = struct.pack("<H", len(p))
    return bytes(p)


def jpeg_header(w, h):
    def dqt(i, t):
        return b"\xff\xdb" + struct.pack(">H", 67) + bytes([i]) + bytes(t)
    sof = (b"\xff\xc0" + struct.pack(">HBHHB", 17, 8, h, w, 3)
           + b"\x01\x11\x00\x02\x11\x01\x03\x11\x01")
    sos = b"\xff\xda" + struct.pack(">HB", 12, 3) + b"\x01\x00\x02\x11\x03\x11\x00\x3f\x00"
    return b"\xff\xd8" + dqt(0, LUMA_QT) + dqt(1, CHROMA_QT) + sof + sos


def local_ip_towards(ip):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((ip, 9))
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def tcp_open(ip, port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.6)
    try:
        return port if s.connect_ex((ip, port)) == 0 else None
    except OSError:
        return None
    finally:
        s.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", default="192.168.169.1")
    ap.add_argument("--seconds", type=float, default=12.0)
    ap.add_argument("--out", default=os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "diagnostico_v166_out"))
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    log("=== Diagnostico V166 === " + time.strftime("%Y-%m-%d %H:%M:%S"))
    log("Python %s en %s" % (sys.version.split()[0], sys.platform))

    my_ip = local_ip_towards(a.ip)
    log("\n[1] Red")
    log("  IP de este PC hacia el dron: %s" % my_ip)
    if not my_ip or my_ip.rsplit(".", 1)[0] != a.ip.rsplit(".", 1)[0]:
        log("  AVISO: no pareces estar en la red del dron (%s.x)." % a.ip.rsplit(".", 1)[0])
        log("  Conectate a la WiFi FLOW_... y vuelve a ejecutar. Sigo de todos modos.")

    log("\n[2] Puertos TCP abiertos en %s" % a.ip)
    with ThreadPoolExecutor(32) as ex:
        opened = [p for p in ex.map(lambda p: tcp_open(a.ip, p), TCP_PORTS) if p]
    log("  " + (", ".join(map(str, opened)) if opened else "ninguno (normal en esta familia: todo va por UDP)"))

    log("\n[3] Video y telemetria por UDP (%.0f s)" % a.seconds)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
    sock.bind(("", 0))
    log("  Puerto local: %d" % sock.getsockname()[1])

    def send_all(pkt):
        for port in UDP_PORTS:
            try:
                sock.sendto(pkt, (a.ip, port))
            except OSError as e:
                log("  error enviando a %d: %s" % (port, e))

    raw = open(os.path.join(a.out, "paquetes.bin"), "wb")
    frames = {}          # seq -> {"total": n, "frags": {i: bytes}, "w": , "h": }
    done = set()
    sources, types, mcu_msgs, others = {}, {}, [], []
    stats = dict(pkts=0, frames=0, saved=0, first=None, last_seq=0)
    info = {}
    asked_version = False
    t0 = time.time()
    last_tx = 0.0
    last_rx = 0.0

    while time.time() - t0 < a.seconds:
        now = time.time()
        started = stats["pkts"] > 0
        if not started and now - last_tx > 0.1:
            send_all(START_STREAM)
            send_all(ack_packet(0, []))
            last_tx = now
        elif started and now - last_rx > 0.3 and now - last_tx > 0.2:
            seq = stats["last_seq"]
            send_all(START_STREAM)
            send_all(ack_packet(seq + 1, [ack_slot(seq, 1, b"\xff" * 4), ack_slot(seq + 1, 3)]))
            last_tx = now

        r, _, _ = select.select([sock], [], [], 0.02)
        if not r:
            continue
        try:
            data, src = sock.recvfrom(65535)
        except OSError:
            continue
        last_rx = time.time()
        stats["pkts"] += 1
        if stats["first"] is None:
            stats["first"] = last_rx - t0
        sources[src] = sources.get(src, 0) + 1
        if stats["pkts"] <= 600:
            raw.write(struct.pack("<dHH", last_rx - t0, src[1], len(data)) + data)
        if not asked_version:
            send_all(ASK_VERSION)
            asked_version = True

        key = data[:2].hex()
        types[key] = types.get(key, 0) + 1

        if len(data) >= 56 and data[0] == 0x93 and data[1] == 0x01:
            seq = struct.unpack_from("<Q", data, 8)[0]
            frag, total, body = struct.unpack_from("<III", data, 32)
            w, h = struct.unpack_from("<HH", data, 44)
            if not info:
                info.update(w=w, h=h, quality=data[48], frags=total, body=body,
                            pkt_len=len(data), cam_main=data[52], cam_flow=data[53],
                            header=data[:56].hex())
            if seq in done or total == 0 or frag >= total:
                continue
            f = frames.setdefault(seq, {"total": total, "frags": {}, "w": w, "h": h})
            f["frags"][frag] = data[56:]
            if len(f["frags"]) == f["total"]:
                done.add(seq)
                stats["frames"] += 1
                stats["last_seq"] = seq
                if stats["saved"] < 5 and stats["frames"] % 10 in (1, 5):
                    body_bytes = b"".join(f["frags"][i] for i in range(f["total"]))
                    stats["saved"] += 1
                    with open(os.path.join(a.out, "frame_%d.jpg" % stats["saved"]), "wb") as fh:
                        fh.write(jpeg_header(f["w"], f["h"]) + body_bytes + b"\xff\xd9")
                    with open(os.path.join(a.out, "frame_%d.cuerpo.bin" % stats["saved"]), "wb") as fh:
                        fh.write(body_bytes)
                frames.pop(seq, None)
                for old in [s for s in frames if s < seq - 4]:
                    frames.pop(old, None)
                send_all(ack_packet(seq + 1, []))
                send_all(ack_packet(seq + 1, [ack_slot(seq, 1, b"\xff" * 4), ack_slot(seq + 1, 3)]))
                last_tx = time.time()
        elif len(data) >= 12 and data[0] == 0x93 and data[1] == 0x04:
            n = struct.unpack_from("<H", data, 8)[0]
            payload = data[12:12 + n]
            if payload not in mcu_msgs and len(mcu_msgs) < 40:
                mcu_msgs.append(payload)
        elif len(others) < 20 and data[:64] not in others:
            others.append(data[:64])

    raw.close()
    sock.close()
    elapsed = time.time() - t0

    log("  Paquetes recibidos: %d" % stats["pkts"])
    if stats["pkts"] == 0:
        log("\n  >>> EL DRON NO RESPONDIO. Causas mas probables:")
        log("      a) El Firewall de Windows bloquea la respuesta (llega desde el puerto 1234).")
        log("         Permite Python en redes publicas, o ejecuta como administrador:")
        log('         netsh advfirewall firewall add rule name="V166" dir=in action=allow protocol=UDP remoteip=192.168.169.1')
        log("      b) El PC no esta en la WiFi FLOW_...")
        log("      c) Este lote usa otra IP: ejecuta 'ipconfig' y pasa la puerta de enlace con --ip")
    else:
        log("  Primer paquete a los %.2f s" % stats["first"])
        for (ip, port), n in sources.items():
            log("  Origen %s:%d -> %d paquetes" % (ip, port, n))
        log("  Tipos (2 primeros bytes): " + ", ".join("%s x%d" % kv for kv in types.items()))
        if info:
            log("  VIDEO: %dx%d, calidad %d, %d fragmentos/cuadro, %d bytes/cuadro" % (
                info["w"], info["h"], info["quality"], info["frags"], info["body"]))
            log("  Estado camaras: principal=%d flujo optico=%d" % (info["cam_main"], info["cam_flow"]))
            log("  Cuadros completos: %d (%.1f fps)" % (stats["frames"], stats["frames"] / max(elapsed - stats["first"], 0.1)))
            log("  Cabecera del primer fragmento: " + info["header"])
        for m in mcu_msgs:
            txt = "".join(chr(b) if 32 <= b < 127 else "." for b in m)
            log("  MCU: %s | %s" % (m.hex(), txt))
        for o in others:
            log("  OTRO: " + o.hex())

    log("\nListo. Resultados en: " + a.out)
    with open(os.path.join(a.out, "resultado.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(LOG) + "\n")


if __name__ == "__main__":
    main()
