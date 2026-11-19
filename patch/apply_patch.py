#!/usr/bin/env python3
"""Patch a Gadgetbridge checkout so it knows about the Infinix XWatch N5 Pro.

Usage:  python3 apply_patch.py <path-to-Gadgetbridge-checkout>

Runs on the GitHub runner, not on Windows. Fails loudly (non-zero exit) if any
anchor is missing, so a CI run cannot silently produce a stock APK.

What it does
  1. DeviceType.java        : registers INFINIX_XWATCH_N5_PRO
  2. XWatchCoordinator.java : narrows the old "XWatch.*" pattern so it no longer claims this watch
  3. strings.xml            : adds the device name string
  4. GBDaoGenerator.java    : adds the database entities (steps+heart-rate activity sample, manual heart-rate
                              sample, stress sample) and raises the schema version by one
  5. copies every .java file next to this script into the right Gadgetbridge folder
"""
import os
import re
import shutil
import sys

root = sys.argv[1] if len(sys.argv) > 1 else "."
J = root + "/app/src/main/java/nodomain/freeyourgadget/gadgetbridge"
HERE = os.path.dirname(os.path.abspath(__file__))


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
    ours = "import nodomain.freeyourgadget.gadgetbridge.devices.infinix.InfinixXWatchCoordinator;\n"
    if t.count(imp) == 1:
        t = t.replace(imp, ours + imp)
    else:
        first = re.search(r"^import [^\n]+;\n", t, re.M)
        if first:
            t = t[: first.start()] + ours + t[first.start():]
        else:
            pkg = re.search(r"^package [^\n]+;\n", t, re.M)
            if not pkg:
                raise SystemExit("no package or import line found in DeviceType.java")
            t = t[: pkg.end()] + "\n" + ours + t[pkg.end():]

    # Clone the existing XWATCH entry so the new entry always has the same shape as the current enum constructor.
    m = re.search(r"^[ \t]*XWATCH\s*\([^\n]*$", t, re.M)
    if m and ")" in m.group(0) and "XWatchCoordinator" in m.group(0):
        line = m.group(0)
        new = line.replace("XWATCH", "INFINIX_XWATCH_N5_PRO", 1).replace("XWatchCoordinator", "InfinixXWatchCoordinator")
        new = re.sub(r'"[^"]*"', '"infinix_xwatch_n5_pro"', new, count=1)
        new = new.rstrip()
        if new.endswith(";"):
            new = new[:-1] + ","
        print("enum entry cloned from:", line.strip())
        print("                   to:", new.strip())
        return t[: m.start()] + new + "\n" + t[m.start():]

    # Fallback: put the entry first in the enum.
    e = re.search(r"public\s+enum\s+DeviceType\b[^{]*\{[ \t]*\n", t)
    if not e:
        lines = [l for l in t.splitlines() if "XWATCH" in l or "XWatch" in l]
        raise SystemExit("could not place the DeviceType entry; lines mentioning XWatch: " + repr(lines[:6]))
    print("XWATCH entry not found in the usual form; inserting the new entry first in the enum")
    return t[: e.end()] + "    INFINIX_XWATCH_N5_PRO(InfinixXWatchCoordinator.class),\n" + t[e.end():]


def old_xwatch(t):
    m = re.search(r'Pattern\.compile\("(XWatch[^"]*)"\)', t)
    if not m:
        lines = [l.strip() for l in t.splitlines() if "Pattern" in l or "XWatch" in l]
        raise SystemExit("old XWatch name pattern not found; candidate lines: " + repr(lines[:6]))
    old = m.group(1)
    new = "XWatch(?! N5 Pro)" + old[len("XWatch"):]
    print("old XWatch pattern", repr(old), "->", repr(new))
    return t[: m.start(1)] + new + t[m.end(1):]


def strings(t):
    if t.count("</resources>") != 1:
        raise SystemExit("expected exactly one </resources> in strings.xml")
    line = (
        '    <string name="devicetype_infinix_xwatch_n5_pro" '
        'translatable="false">Infinix XWatch N5 Pro</string>\n'
    )
    return t.replace("</resources>", line + "</resources>")


ENTITY_METHODS = '''    private static Entity addInfinixXWatchActivitySample(Schema schema, Entity user, Entity device) {
        Entity activitySample = addEntity(schema, "InfinixXWatchActivitySample");
        addCommonActivitySampleProperties("AbstractInfinixXWatchActivitySample", activitySample, user, device);
        activitySample.implementsSerializable();
        activitySample.addIntProperty(SAMPLE_RAW_KIND).notNull().codeBeforeGetterAndSetter(OVERRIDE);
        activitySample.addIntProperty(SAMPLE_STEPS).notNull().codeBeforeGetterAndSetter(OVERRIDE);
        addHeartRateProperties(activitySample);
        return activitySample;
    }

    private static Entity addInfinixXWatchHeartRateSample(Schema schema, Entity user, Entity device) {
        Entity heartRateSample = addEntity(schema, "InfinixXWatchHeartRateSample");
        heartRateSample.implementsSerializable();
        addCommonTimeSampleProperties("AbstractHeartRateSample", heartRateSample, user, device);
        // getter only: the HeartRateSample interface has no setHeartRate(), so a setter @Override would not compile
        heartRateSample.addIntProperty(SAMPLE_HEART_RATE).notNull().codeBeforeGetter(OVERRIDE);
        return heartRateSample;
    }

    private static Entity addInfinixXWatchSpo2Sample(Schema schema, Entity user, Entity device) {
        Entity spo2sample = addEntity(schema, "InfinixXWatchSpo2Sample");
        addCommonTimeSampleProperties("AbstractSpo2Sample", spo2sample, user, device);
        spo2sample.addIntProperty(SAMPLE_SPO2).notNull().codeBeforeGetter(OVERRIDE);
        return spo2sample;
    }

    private static Entity addInfinixXWatchStressSample(Schema schema, Entity user, Entity device) {
        Entity stressSample = addEntity(schema, "InfinixXWatchStressSample");
        addCommonTimeSampleProperties("AbstractStressSample", stressSample, user, device);
        stressSample.addIntProperty(SAMPLE_STRESS).notNull().codeBeforeGetter(OVERRIDE);
        return stressSample;
    }

'''

ENTITY_CALLS = '''        addInfinixXWatchActivitySample(schema, user, device);
        sampleProvidersToGenerate.add(addInfinixXWatchHeartRateSample(schema, user, device));
        sampleProvidersToGenerate.add(addInfinixXWatchStressSample(schema, user, device));
        sampleProvidersToGenerate.add(addInfinixXWatchSpo2Sample(schema, user, device));
'''


def dao_generator(t):
    # 1) schema version + 1 (whatever it currently is)
    m = re.search(r"new Schema\((\d+),", t)
    if not m:
        raise SystemExit("schema version not found in GBDaoGenerator")
    t = t.replace(m.group(0), "new Schema(%d," % (int(m.group(1)) + 1), 1)
    print("schema version %s -> %d" % (m.group(1), int(m.group(1)) + 1))

    # 2) entity registrations, placed before an existing activity-sample registration
    m = re.search(r"^[ \t]*addColmiActivitySample\(schema, user, device\);[ \t]*\n", t, re.M) or \
        re.search(r"^[ \t]*add\w+ActivitySample\(schema, user, device\);[ \t]*\n", t, re.M)
    if not m:
        lines = [l.strip() for l in t.splitlines() if "ActivitySample(schema" in l]
        raise SystemExit("no activity-sample registration found in GBDaoGenerator; candidates: " + repr(lines[:6]))
    print("registrations placed before:", m.group(0).strip())
    t = t[: m.start()] + ENTITY_CALLS + t[m.start():]

    # 3) entity definitions, placed before an existing activity-sample definition
    d = re.search(r"^[ \t]*private static Entity addColmiActivitySample\(Schema schema, Entity user, Entity device\)[^\n]*\{[ \t]*\n", t, re.M) or \
        re.search(r"^[ \t]*private static Entity add\w+ActivitySample\(Schema schema, Entity user, Entity device\)[^\n]*\{[ \t]*\n", t, re.M)
    if not d:
        raise SystemExit("no activity-sample definition found in GBDaoGenerator")
    print("definitions placed before:", d.group(0).strip())
    t = t[: d.start()] + ENTITY_METHODS + t[d.start():]

    # 4) the constants the new code uses must exist
    for name in ("SAMPLE_RAW_KIND", "SAMPLE_STEPS", "SAMPLE_STRESS", "SAMPLE_HEART_RATE", "SAMPLE_SPO2", "OVERRIDE"):
        if name not in t:
            raise SystemExit("constant " + name + " not found in GBDaoGenerator")
    return t


def extract_method(text, name):
    """Returns (method_text_with_annotations, imports_needed) for the first declaration of `name`, or (None, [])."""
    lines = text.splitlines(keepends=True)
    decl = next((i for i, l in enumerate(lines) if (name + "(") in l and "public" in l), None)
    if decl is None:
        return None, []
    start = decl
    while start > 0 and lines[start - 1].strip().startswith("@"):
        start -= 1
    body = "".join(lines[decl:])
    brace = body.find("{")
    if brace < 0:
        return None, []
    depth = 0
    end = None
    for i in range(brace, len(body)):
        if body[i] == "{":
            depth += 1
        elif body[i] == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    if end is None:
        return None, []
    method = "".join(lines[start:decl]) + body[:end] + "\n"
    method = re.sub(r"\bXWatchSupport\b", "InfinixXWatchSupport", method)
    imports = []
    for m in re.finditer(r"^import ([\w.]+\.(\w+));\n", text, re.M):
        simple = m.group(2)
        if simple not in ("XWatchSupport", "XWatchCoordinator") and re.search(r"\b" + simple + r"\b", method):
            imports.append(m.group(0))
    return method, imports


XWATCH_CREATE_SUPPORT = {}


def remember_create_support(t):
    method, imports = extract_method(t, "createDeviceSupport")
    XWATCH_CREATE_SUPPORT["method"] = method
    XWATCH_CREATE_SUPPORT["imports"] = imports
    print("createDeviceSupport copied from the old XWatch coordinator:", "yes" if method else "NOT FOUND")
    return t


def install_create_support(path):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    marker = "    // @@CREATE_DEVICE_SUPPORT@@\n"
    if marker not in text:
        return
    method = XWATCH_CREATE_SUPPORT.get("method")
    if method:
        indented = "".join(("    " + l if l.strip() and not l.startswith("    ") else l) for l in method.splitlines(keepends=True))
        text = text.replace(marker, indented)
        first = re.search(r"^import [^\n]+;\n", text, re.M)
        extra = "".join(i for i in XWATCH_CREATE_SUPPORT["imports"] if i not in text)
        if extra and first:
            text = text[: first.start()] + extra + text[first.start():]
    else:
        text = text.replace(marker, "")
        print("WARNING: no createDeviceSupport found in the old XWatch coordinator; the build may ask for one")
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print("createDeviceSupport installed in", path)


def manifest(t):
    """Adds .wf to the file types Gadgetbridge's installer activity accepts (same style as the existing .fw entries)."""
    def patterns(ext):
        return ["/.*" + "\\\\..*" * k + "\\\\." + ext for k in range(11)]

    act = t.find("activities.install.FwAppInstallerActivity")
    if act < 0:
        raise SystemExit("FwAppInstallerActivity not found in AndroidManifest.xml")
    end = t.find("</activity>", act)
    block = t[act:end]
    m = re.search(r'^([ \t]*)<data android:pathPattern="/\.\*\\\\\.fw" />\n', block, re.M)
    if m:
        indent = m.group(1)
        new = "".join('%s<data android:pathPattern="%s" />\n' % (indent, pat) for pat in patterns("wf"))
        pos = act + m.start()
        print("manifest: .wf patterns added next to the .fw ones")
        return t[:pos] + new + t[pos:]
    # fallback: a separate filter at the end of the activity
    new = ('        <intent-filter>\n'
           '            <action android:name="android.intent.action.VIEW" />\n'
           '            <category android:name="android.intent.category.DEFAULT" />\n'
           '            <data android:scheme="content" />\n'
           '            <data android:scheme="file" />\n'
           '            <data android:mimeType="*/*" />\n'
           + "".join('            <data android:pathPattern="%s" />\n' % pat for pat in patterns("wf")) +
           '        </intent-filter>\n    ')
    print("manifest: .fw patterns not found; added a separate filter for .wf")
    return t[:end] + new + t[end:]


def find_generator():
    for dirpath, _dirs, files in os.walk(root):
        if "GBDaoGenerator.java" in files:
            return os.path.join(dirpath, "GBDaoGenerator.java")
    raise SystemExit("GBDaoGenerator.java not found")


# where each hand-written file goes
DESTINATIONS = {
    "InfinixXWatchCoordinator.java": "devices/infinix",
    "InfinixXWatchActivitySampleProvider.java": "devices/infinix",
    "InfinixXWatchInstallHandler.java": "devices/infinix",
    "AbstractInfinixXWatchActivitySample.java": "entities",
    "InfinixXWatchSupport.java": "service/devices/infinix",
    "XWatchProtocol.java": "service/devices/infinix",
}

def find_file(name):
    for dirpath, _dirs, files in os.walk(J):
        if name in files:
            return os.path.join(dirpath, name)
    raise SystemExit(name + " not found")


edit(find_file("DeviceType.java"), device_type)
_xw = find_file("XWatchCoordinator.java")
with open(_xw, encoding="utf-8") as _f:
    remember_create_support(_f.read())
edit(_xw, old_xwatch)
edit(root + "/app/src/main/res/values/strings.xml", strings)
edit(root + "/app/src/main/AndroidManifest.xml", manifest)
edit(find_generator(), dao_generator)

for name, folder in DESTINATIONS.items():
    src = os.path.join(HERE, name)
    if not os.path.exists(src):
        raise SystemExit("missing source file next to apply_patch.py: " + name)
    os.makedirs(os.path.join(J, folder), exist_ok=True)
    shutil.copyfile(src, os.path.join(J, folder, name))
    print("copied", name, "->", folder)

install_create_support(os.path.join(J, "devices/infinix/InfinixXWatchCoordinator.java"))
