#!/usr/bin/env python3
"""Patch a Gadgetbridge checkout so it knows about the Infinix XWatch N5 Pro.

Usage:  python3 apply_patch.py <path-to-Gadgetbridge-checkout>

Fails loudly (non-zero exit) if any anchor is missing, so a CI run cannot
silently produce a stock APK.

Why the old XWatch driver must be narrowed
------------------------------------------
Gadgetbridge already ships an XWatchCoordinator whose supported-name pattern
is `XWatch.*`.  That pattern ALSO matches "XWatch N5 Pro".  Device selection
is driven by these patterns (NOT by the order of DeviceType enum values), so
the only reliable fix is to narrow the old pattern with a negative lookahead:

    XWatch(?! N5 Pro).*      # matches XWatch, XWatch 2, ... but NOT XWatch N5 Pro

Adding INFINIX_XWATCH_N5_PRO before XWATCH in the enum is cosmetic and is done
for tidiness only.
"""
import sys

root = sys.argv[1] if len(sys.argv) > 1 else "."
J = root + "/app/src/main/java/nodomain/freeyourgadget/gadgetbridge"

def edit(path, fn):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    new = fn(text)
    if new == text:
        raise SystemExit("no change made to " + path + " (already patched?)")
    with open(path, "w", encoding="utf-8") as f:
        f.write(new)
    print("patched", path)

def device_type(t):
    imp = "import nodomain.freeyourgadget.gadgetbridge.devices.xwatch.XWatchCoordinator;\n"
    ent = "    XWATCH(XWatchCoordinator.class),\n"
    if t.count(imp) != 1 or t.count(ent) != 1:
        raise SystemExit(
            "DeviceType anchors not found exactly once "
            f"(import={t.count(imp)}, entry={t.count(ent)})"
        )
    t = t.replace(
        imp,
        "import nodomain.freeyourgadget.gadgetbridge.devices.infinix.InfinixXWatchCoordinator;\n" + imp,
    )
    # placed before XWATCH for tidiness; pattern narrowing is what actually decides
    return t.replace(
        ent, "    INFINIX_XWATCH_N5_PRO(InfinixXWatchCoordinator.class),\n" + ent
    )

def old_xwatch(t):
    old = 'Pattern.compile("XWatch.*")'
    if t.count(old) != 1:
        raise SystemExit(
            f'old XWatch pattern not found exactly once (found {t.count(old)})'
        )
    return t.replace(old, 'Pattern.compile("XWatch(?! N5 Pro).*")')

def strings(t):
    if t.count("</resources>") != 1:
        raise SystemExit("expected exactly one </resources> in strings.xml")
    line = (
        '    <string name="devicetype_infinix_xwatch_n5_pro" '
        'translatable="false">Infinix XWatch N5 Pro</string>\n'
    )
    return t.replace("</resources>", line + "</resources>")

edit(J + "/model/DeviceType.java", device_type)
edit(J + "/devices/xwatch/XWatchCoordinator.java", old_xwatch)
edit(root + "/app/src/main/res/values/strings.xml", strings)
