"""Prints one line per запрет strategy: name<TAB>byedpi arguments.

Exists so the strategies can be probed against the real binary on a phone. They are ours to
invent — byedpi refuses to start on a combination it does not like, and it says so only in
logcat, which is exactly the kind of thing nobody notices until the mode does nothing.

    python build\\dump-byedpi-args.py > args.txt
"""
import re
import pathlib

KT = pathlib.Path(__file__).resolve().parent.parent / (
    "android/app/src/main/java/cc/moon/internet/core/ZapretStrategies.kt")

FAKE_SNI = re.search(r'FAKE_SNI\s*=\s*"([^"]+)"', KT.read_text(encoding="utf-8")).group(1)

for m in re.finditer(r'^\s*s\("([^"]+)",\s*(.+?)\),\s*$', KT.read_text(encoding="utf-8"), re.M):
    # every argument is either a quoted literal or the FAKE_SNI constant
    args = [FAKE_SNI if tok == "FAKE_SNI" else tok.strip('"')
            for tok in re.findall(r'"[^"]*"|FAKE_SNI', m.group(2))]
    print(m.group(1) + "\t" + " ".join(args))
