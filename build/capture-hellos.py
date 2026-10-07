"""Capture the ClientHello xray sends for each uTLS fingerprint, for Moon Ping.

Moon Ping sends these bytes (with the server's SNI) to tell a server the censor has banned — TCP
still completes, the TLS hello is swallowed — from one that is fine. They must be xray's own, so
they are captured from xray: it dials 127.0.0.1:4443 as a VLESS+TLS server, we keep its first TLS
record and hang up. Re-run after updating xray, then commit both copies of client-hellos.txt.

Usage:  python build/capture-hellos.py [path\\to\\xray.exe]
"""
import base64, json, os, socket, subprocess, sys, tempfile, threading, time, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
XRAY = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "cores", "xray", "xray.exe")
FPS = ["firefox", "chrome", "safari", "ios", "edge", "android", "qq", "360", "go"]   # go = no uTLS
OUT = [os.path.join(ROOT, "src", "MoonInternet.Core", "Resources", "client-hellos.txt"),
       os.path.join(ROOT, "android", "app", "src", "main", "assets", "client-hellos.txt")]


def grab(srv):
    conn, _ = srv.accept()
    conn.settimeout(5)
    data = b""
    with conn:
        while len(data) < 5 or len(data) < 5 + int.from_bytes(data[3:5], "big"):
            chunk = conn.recv(65536)
            if not chunk:
                break
            data += chunk
    return data


def capture(fp):
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 4443))
    srv.listen(1)
    tls = {"serverName": "probe.example.com"}
    if fp != "go":
        tls["fingerprint"] = fp
    cfg = {
        "log": {"loglevel": "none"},
        "inbounds": [{"port": 10899, "listen": "127.0.0.1", "protocol": "http"}],
        "outbounds": [{
            "protocol": "vless",
            "settings": {"vnext": [{"address": "127.0.0.1", "port": 4443,
                                    "users": [{"id": "00000000-0000-0000-0000-000000000000", "encryption": "none"}]}]},
            "streamSettings": {"network": "raw", "security": "tls", "tlsSettings": tls},
        }],
    }
    path = os.path.join(tempfile.gettempdir(), "moon-hello-cfg.json")
    with open(path, "w") as f:
        json.dump(cfg, f)
    xr = subprocess.Popen([XRAY, "run", "-c", path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        time.sleep(1.5)
        result = {}
        t = threading.Thread(target=lambda: result.setdefault("hello", grab(srv)))
        t.start()
        try:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({"http": "http://127.0.0.1:10899"}))
            opener.open("http://example.com/", timeout=4)
        except Exception:
            pass
        t.join(6)
        return result.get("hello", b"")
    finally:
        xr.kill()
        srv.close()


version = subprocess.run([XRAY, "version"], capture_output=True, text=True).stdout.splitlines()[0]
lines = [f"# ClientHellos {version.split('(')[0].strip()} sends per uTLS fingerprint, captured by build/capture-hellos.py.",
         "# One per line: <fingerprint> <base64 of the whole TLS record>. SNI and randoms are rewritten per probe."]
for fp in FPS:
    hello = capture(fp)
    if hello[:1] != b"\x16" or len(hello) != 5 + int.from_bytes(hello[3:5], "big"):
        sys.exit(f"{fp}: no ClientHello captured")
    print(f"{fp:8} {len(hello)} bytes")
    lines.append(f"{fp} {base64.b64encode(hello).decode()}")
for out in OUT:
    with open(out, "w", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
print("written:", *OUT, sep="\n  ")
