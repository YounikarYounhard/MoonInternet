# Adds a second cached demo subscription to a local dev build, so the list has two plates to
# reorder while the movement animation is being looked at. Touches only the build output's own
# save folder — never an installed copy.
import copy
import io
import json
import sys

path = sys.argv[1] if len(sys.argv) > 1 else \
    "src/MoonInternet.App/bin/Release/net9.0-windows/save/settings.json"

with io.open(path, encoding="utf-8-sig") as f:
    settings = json.load(f)

first = settings["CachedSubs"][0]
if any(c["Url"].endswith("/sub2") for c in settings["CachedSubs"]):
    print("already there")
    raise SystemExit

second = copy.deepcopy(first)
second["Url"] = "https://demo.example.net/sub2"
second["Name"] = "Demo VPN 2"
second["TrafficText"] = "11,4 GB / 100 GB"
# Half the servers, so the two plates differ at a glance and differ in height.
second["Links"] = [link for i, link in enumerate(first["Links"]) if i % 2 == 0]

settings["CachedSubs"].append(second)
settings["SubscriptionUrls"].append(second["Url"])

with io.open(path, "w", encoding="utf-8-sig", newline="") as f:
    f.write(json.dumps(settings, ensure_ascii=False, indent=2))

print("added", second["Name"], "-", len(second["Links"]), "of", len(first["Links"]), "servers")
