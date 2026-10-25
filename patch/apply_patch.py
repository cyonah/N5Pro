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
    return t.replace(ent, "    INFINIX_XWATCH_N5_PRO(InfinixXWatchCoordinator.class),\n" + ent)


def old_xwatch(t):
    old = 'Pattern.compile("XWatch.*")'
    if t.count(old) != 1:
        raise SystemExit(f'old XWatch pattern not found exactly once (found {t.count(old)})')
    return t.replace(old, 'Pattern.compile("XWatch(?! N5 Pro).*")')


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
'''


def dao_generator(t):
    # 1) schema version + 1 (whatever it currently is)
    m = re.search(r"new Schema\((\d+),", t)
    if not m:
        raise SystemExit("schema version not found in GBDaoGenerator")
    t = t.replace(m.group(0), "new Schema(%d," % (int(m.group(1)) + 1), 1)
    print("schema version %s -> %d" % (m.group(1), int(m.group(1)) + 1))

    # 2) entity registrations, placed before the Colmi activity sample registration
    call = "        addColmiActivitySample(schema, user, device);\n"
    if t.count(call) != 1:
        raise SystemExit(f"Colmi registration anchor not found exactly once (found {t.count(call)})")
    t = t.replace(call, ENTITY_CALLS + call)

    # 3) entity definitions, placed before the Colmi activity sample definition
    definition = "    private static Entity addColmiActivitySample(Schema schema, Entity user, Entity device) {\n"
    if t.count(definition) != 1:
        raise SystemExit(f"Colmi definition anchor not found exactly once (found {t.count(definition)})")
    t = t.replace(definition, ENTITY_METHODS + definition)

    # 4) the constants the new code uses must exist
    for name in ("SAMPLE_RAW_KIND", "SAMPLE_STEPS", "SAMPLE_STRESS", "SAMPLE_HEART_RATE", "OVERRIDE"):
        if name not in t:
            raise SystemExit("constant " + name + " not found in GBDaoGenerator")
    return t


def find_generator():
    for dirpath, _dirs, files in os.walk(root):
        if "GBDaoGenerator.java" in files:
            return os.path.join(dirpath, "GBDaoGenerator.java")
    raise SystemExit("GBDaoGenerator.java not found")


# where each hand-written file goes
DESTINATIONS = {
    "InfinixXWatchCoordinator.java": "devices/infinix",
    "InfinixXWatchActivitySampleProvider.java": "devices/infinix",
    "AbstractInfinixXWatchActivitySample.java": "entities",
    "InfinixXWatchSupport.java": "service/devices/infinix",
    "XWatchProtocol.java": "service/devices/infinix",
}

edit(J + "/model/DeviceType.java", device_type)
edit(J + "/devices/xwatch/XWatchCoordinator.java", old_xwatch)
edit(root + "/app/src/main/res/values/strings.xml", strings)
edit(find_generator(), dao_generator)

for name, folder in DESTINATIONS.items():
    src = os.path.join(HERE, name)
    if not os.path.exists(src):
        raise SystemExit("missing source file next to apply_patch.py: " + name)
    os.makedirs(os.path.join(J, folder), exist_ok=True)
    shutil.copyfile(src, os.path.join(J, folder, name))
    print("copied", name, "->", folder)
