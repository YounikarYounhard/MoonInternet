"""Connects to a host THROUGH a SOCKS5 proxy and completes a TLS handshake.

Written to test byedpi on the phone from the desktop over `adb forward`: it answers the only
question that matters for a Zapret strategy — does a blocked site actually open through it —
without anybody having to tap through the app.

    python build\\probe-socks.py 127.0.0.1 11080 www.youtube.com
"""
import socket
import ssl
import struct
import sys
import time


def socks5_connect(proxy_host, proxy_port, host, port, timeout=8.0):
    """Returns a socket already tunnelled to host:port, or raises."""
    s = socket.create_connection((proxy_host, proxy_port), timeout)
    s.settimeout(timeout)
    s.sendall(b"\x05\x01\x00")                      # version 5, one method, no auth
    if s.recv(2) != b"\x05\x00":
        raise RuntimeError("proxy refused the no-auth method")

    name = host.encode()
    # ATYP 3 = domain name: byedpi has to see the hostname, an IP tells it nothing about
    # which connection to work on.
    s.sendall(b"\x05\x01\x00\x03" + bytes([len(name)]) + name + struct.pack(">H", port))
    reply = s.recv(4)
    if len(reply) < 2 or reply[1] != 0:
        raise RuntimeError("proxy said no: code %s" % (reply[1] if len(reply) > 1 else "?"))
    # drain the bound address the reply carries
    if reply[3] == 1:
        s.recv(4 + 2)
    elif reply[3] == 3:
        s.recv(s.recv(1)[0] + 2)
    else:
        s.recv(16 + 2)
    return s


def main():
    proxy_host, proxy_port, host = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    started = time.time()
    try:
        raw = socks5_connect(proxy_host, proxy_port, host, 443)
    except Exception as e:
        print("SOCKS: FAIL — %s" % e)
        return 1
    print("SOCKS: ok (%d ms)" % ((time.time() - started) * 1000))

    # The handshake is the part DPI interferes with; a bare connect completes either way.
    try:
        ctx = ssl.create_default_context()
        with ctx.wrap_socket(raw, server_hostname=host) as tls:
            print("TLS:   ok (%d ms) — %s" % ((time.time() - started) * 1000, tls.version()))
        return 0
    except Exception as e:
        print("TLS:   FAIL — %s" % e)
        return 1


if __name__ == "__main__":
    sys.exit(main())
