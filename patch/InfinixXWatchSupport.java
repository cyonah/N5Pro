/*  Infinix XWatch N5 Pro support for Gadgetbridge (experimental).

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.infinix;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.InfinixXWatchHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.InfinixXWatchSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.InfinixXWatchStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.InfinixXWatchActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.InfinixXWatchActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.InfinixXWatchHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.InfinixXWatchSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.InfinixXWatchStressSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.UriHelper;

/**
 * Infinix XWatch N5 Pro support.
 *
 * Derived from a LightBlue capture (LBX_LOGS_2026-10-22_14-56):
 *   - no bind handshake exists; the watch answers CMD_VERSION (0x32) directly
 *   - the version reply arrives as a notification on CHAR_COMMAND (.9231)
 *   - CHAR_SECONDARY (.9232) accepts writes but does not echo command replies
 *   - the watch advertises the standard Heart Rate service (180d / 2a37)
 *
 * IMPORTANT: everything must go into the single TransactionBuilder that
 * initializeDevice() returns.  Building a second transaction inside this
 * method and queueing it manually leaves the device stuck in INITIALIZING,
 * because the framework's own initialization transaction never gets the
 * SetDeviceStateAction(INITIALIZED) it expects.
 */
public class InfinixXWatchSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(InfinixXWatchSupport.class);

    /** Wait after enabling notifications before the first command write. */
    private static final int NOTIFY_SETTLE_MS = 2000;
    private static final int SLEEP_STEP_SECONDS = 60;
    private static final int HOURLY_BUCKET_SECONDS = 3600;

    private final Map<UUID, XWatchProtocol.Reassembler> reassemblers = new HashMap<>();

    public InfinixXWatchSupport() {
        super(LOG);
        addSupportedService(XWatchProtocol.SERVICE);
        // Let Gadgetbridge handle the standard Heart Rate service on its own.
        addSupportedService(GattService.SERVICE_HEART_RATE.uuid);

        reassemblers.put(XWatchProtocol.CHAR_COMMAND, new XWatchProtocol.Reassembler());
        reassemblers.put(XWatchProtocol.CHAR_SECONDARY, new XWatchProtocol.Reassembler());
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        for (final XWatchProtocol.Reassembler r : reassemblers.values()) {
            r.reset();
        }

        // The command characteristic only offers "Write Without Response".
        final BluetoothGattCharacteristic command = getCharacteristic(XWatchProtocol.CHAR_COMMAND);
        if (command != null) {
            command.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        } else {
            LOG.warn("command characteristic {} not found", XWatchProtocol.CHAR_COMMAND);
        }

        // Avoids a database error if the first save happens before the version reply arrives.
        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");

        final long nowMillis = System.currentTimeMillis();
        // Hypothesis from the capture: timezone value = offset in hours * 100.
        final int tzValue = (int) (TimeZone.getDefault().getOffset(nowMillis) / 3_600_000L * 100L);

        // One transaction only. The sleep between notify() and write() lets the
        // two descriptor writes complete before the first command goes out,
        // which is what the capture showed.
        LOG.info("initializeDevice: queueing notify, version, battery, time sync (tz value {}), history queries", tzValue);
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        resetFaceTransfer();
        builder.notify(XWatchProtocol.CHAR_COMMAND, true);
        builder.notify(XWatchProtocol.CHAR_SECONDARY, true);
        builder.sleep(NOTIFY_SETTLE_MS);
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.versionRequest());
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.batteryRequest());
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.timeSync(nowMillis / 1000L, tzValue));

        final String lang = Locale.getDefault().getLanguage();
        if (lang != null && !lang.isEmpty()) {
            builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.languageSync(lang));
        }
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.goalQuery(0));

        // Read-only queries for "today" and "history". The watch answers each with an index; the
        // checkpoint reply below makes it send the records, which are then stored for the dashboard.
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_STEPS, XWatchProtocol.QUERY_TODAY));
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_HEART_RATE, XWatchProtocol.QUERY_TODAY));
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_SLEEP, XWatchProtocol.QUERY_TODAY));
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_STEPS, XWatchProtocol.QUERY_HISTORY));
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_HEART_RATE, XWatchProtocol.QUERY_HISTORY));
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_SLEEP, XWatchProtocol.QUERY_HISTORY));
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.buildFrame(XWatchProtocol.CMD_DIAL_QUERY, new byte[0]));
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    @Override
    public boolean onCharacteristicChanged(final BluetoothGatt gatt,
                                           final BluetoothGattCharacteristic characteristic,
                                           final byte[] value) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }
        final UUID uuid = characteristic.getUuid();
        final XWatchProtocol.Reassembler reassembler = reassemblers.get(uuid);
        if (reassembler == null) {
            LOG.debug("unhandled characteristic {}: {}", uuid, GB.hexdump(value));
            return false;
        }
        for (final XWatchProtocol.Frame frame : reassembler.feed(value)) {
            try {
                handleFrame(uuid, frame);
            } catch (final RuntimeException e) {
                LOG.error("failed to handle frame cmd 0x{}", Integer.toHexString(frame.cmd), e);
            }
        }
        return true;
    }

    private void sendFrame(final String taskName, final byte[] frame) {
        final TransactionBuilder b = createTransactionBuilder(taskName);
        b.write(XWatchProtocol.CHAR_COMMAND, frame);
        b.queue();
    }

    /** The "sync" button: ask for today's and the stored history again. */
    @Override
    public void onFetchRecordedData(final int dataTypes) {
        LOG.info("fetch requested (dataTypes={})", dataTypes);
        final TransactionBuilder b = createTransactionBuilder("fetch history");
        for (final int query : new int[]{XWatchProtocol.QUERY_TODAY, XWatchProtocol.QUERY_HISTORY}) {
            b.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_STEPS, query));
            b.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_HEART_RATE, query));
            b.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_SLEEP, query));
        }
        b.queue();
    }

    @Override
    public void onNotification(final NotificationSpec notificationSpec) {
        // TODO: Encode notification payload once the XW5E notification cmd & protobuf fields are captured.
        LOG.debug("notification received for watch: {}", notificationSpec);
    }

    @Override
    public void onSetCallState(final CallSpec callSpec) {
        // TODO: Encode call state payload once captured from MyHealth + btsnoop.
        LOG.debug("call state update: {}", callSpec);
    }

    private void handleFrame(final UUID source, final XWatchProtocol.Frame f) {
        LOG.debug("{} frame cmd=0x{} flags=0x{} idx={} len={}", source,
                Integer.toHexString(f.cmd), Integer.toHexString(f.flags), f.idx, f.payload.length);

        if (f.cmd == XWatchProtocol.CMD_STEPS || f.cmd == XWatchProtocol.CMD_HEART_RATE || f.cmd == XWatchProtocol.CMD_SLEEP) {
            // The watch first answers a history query with an index (date + sample count). The vendor app then
            // writes back a checkpoint message, after which the watch sends the real records (as seen in the capture).
            final List<XWatchProtocol.IndexEntry> index = XWatchProtocol.decodeIndex(f.cmd, f.payload);
            if (!index.isEmpty()) {
                LOG.info("answering index of cmd 0x{} with {} checkpoint entries", Integer.toHexString(f.cmd), index.size());
                sendFrame("checkpoint 0x" + Integer.toHexString(f.cmd), XWatchProtocol.checkpoint(f.cmd, index));
            }
        }

        switch (f.cmd) {
            case XWatchProtocol.CMD_GOAL_QUERY:
            case XWatchProtocol.CMD_GOAL_SET: {
                long idx = -1;
                long val = -1;
                for (final XWatchProtocol.Field fld : XWatchProtocol.parse(f.payload)) {
                    if (!fld.isVarint()) continue;
                    if (fld.number == 1) idx = fld.value;
                    if (fld.number == 2) val = fld.value;
                }
                LOG.info("goal/setting cmd 0x{} index={} value={}", Integer.toHexString(f.cmd), idx, val);
                break;
            }
            case XWatchProtocol.CMD_LANGUAGE: {
                LOG.info("language cmd 0x61 reply payload={}", GB.hexdump(f.payload));
                break;
            }
            case XWatchProtocol.CMD_VERSION: {
                final String version = XWatchProtocol.decodeVersion(f.payload);
                LOG.info("got firmware version {}", version);
                if (version != null) {
                    final GBDeviceEventVersionInfo event = new GBDeviceEventVersionInfo();
                    event.fwVersion = version;
                    handleGBDeviceEvent(event);
                }
                break;
            }
            case XWatchProtocol.CMD_BATTERY: {
                final int level = XWatchProtocol.decodeBattery(f.payload);
                LOG.info("got battery level {}", level);
                if (level >= 0 && level <= 100) {
                    final GBDeviceEventBatteryInfo event = new GBDeviceEventBatteryInfo();
                    event.level = level;
                    handleGBDeviceEvent(event);
                }
                break;
            }
            case XWatchProtocol.CMD_BIND_REPLY:
                handleEvent(f);
                break;
            case XWatchProtocol.CMD_RESPONSE: {
                long eventId = -1;
                long code = 0;
                for (final XWatchProtocol.Field fld : XWatchProtocol.parse(f.payload)) {
                    if (fld.number == 1 && fld.isVarint()) {
                        eventId = fld.value;
                    } else if (fld.number == 2 && fld.isVarint()) {
                        code = fld.value;
                    }
                }
                LOG.info("response to cmd 0x{}: code {}", Long.toHexString(eventId), code);
                onFileTransferAck(eventId, code);
                break;
            }
            case XWatchProtocol.CMD_DIAL_QUERY: {
                long type = -1;
                long index = -1;
                long round = -1;
                for (final XWatchProtocol.Field fld : XWatchProtocol.parse(f.payload)) {
                    if (!fld.isVarint()) continue;
                    if (fld.number == 1) type = fld.value;
                    if (fld.number == 2) index = fld.value;
                    if (fld.number == 3) round = fld.value;
                }
                LOG.info("active watch face: type {} (1 = built in, 2 = downloaded) index {} round {}", type, index, round);
                break;
            }
            case XWatchProtocol.CMD_STEPS: {
                final List<XWatchProtocol.StepDay> days = XWatchProtocol.decodeSteps(f.payload);
                for (final XWatchProtocol.StepDay d : days) {
                    LOG.info("steps {}: total {} in {} buckets", new Date(d.date * 1000L), d.total(), d.steps.size());
                }
                saveSteps(days);
                break;
            }
            case XWatchProtocol.CMD_HEART_RATE: {
                final List<XWatchProtocol.HeartRateDay> days = XWatchProtocol.decodeHeartRate(f.payload);
                for (final XWatchProtocol.HeartRateDay d : days) {
                    int nonZero = 0;
                    for (final byte b : d.readings) {
                        if (b != 0) nonZero++;
                    }
                    LOG.info("heart rate {}: {} slots, {} non-zero", new Date(d.date * 1000L), d.readings.length, nonZero);
                }
                saveHeartRate(days);
                break;
            }
            case XWatchProtocol.CMD_SLEEP: {
                final List<XWatchProtocol.SleepDay> days = XWatchProtocol.decodeSleep(f.payload);
                for (final XWatchProtocol.SleepDay d : days) {
                    for (final XWatchProtocol.SleepSegment s : d.segments) {
                        LOG.info("sleep {} -> {}: {} min state {} category {}", new Date(s.start * 1000L),
                                new Date(s.end * 1000L), s.minutes, s.state, s.category);
                    }
                }
                saveSleep(days);
                break;
            }
            default:
                LOG.debug("cmd 0x{} payload {}", Integer.toHexString(f.cmd), GB.hexdump(f.payload));
        }
    }

    // ---------------------------------------------------------------- unsolicited events (cmd 0x91)

    /** Sent about every 20 s with one value; it matched the battery reply (57 and 57), so it is treated as battery level. */
    private static final long EVENT_BATTERY_LEVEL = 2001;
    /**
     * Blood-oxygen test. Inner message: start = {2: {1: time}}; result = {1: 1, 2: {1: time, 2: percent}},
     * confirmed with a watch reading of 97 % (bytes 0x61).
     */
    private static final long EVENT_BLOOD_OXYGEN_TEST = 2003;
    private static final long EVENT_HEART_RATE_TEST = 2002;
    private static final long EVENT_LIVE_STEPS = 2006;
    private static final long EVENT_STRESS_TEST = 2021;
    /** The watch repeats each result, once about 5 s and once about 30 s later. */
    private static final long DUPLICATE_WINDOW_MS = 120_000L;

    private long lastSpo2Timestamp = -1;
    private long lastHeartRateMillis;
    private long lastHeartRateValue = -1;
    private long lastStressMillis;
    private long lastStressValue = -1;

    private void handleEvent(final XWatchProtocol.Frame f) {
        long eventId = -1;
        byte[] inner = null;
        for (final XWatchProtocol.Field fld : XWatchProtocol.parse(f.payload)) {
            if (fld.number == 1 && fld.isVarint()) {
                eventId = fld.value;
            } else if (fld.number == 2 && !fld.isVarint()) {
                inner = fld.data;
            }
        }
        long status = -1;
        long value = -1;
        final StringBuilder desc = new StringBuilder();
        if (inner != null) {
            try {
                for (final XWatchProtocol.Field x : XWatchProtocol.parse(inner)) {
                    desc.append(" f").append(x.number).append('=')
                            .append(x.isVarint() ? Long.toString(x.value) : "bytes" + x.data.length);
                    if (x.isVarint() && x.number == 1) status = x.value;
                    if (x.isVarint() && x.number == 2) value = x.value;
                }
            } catch (final RuntimeException ignored) {
                desc.append(" (not a plain message, ").append(inner.length).append(" bytes)");
            }
        }
        LOG.info("event {}:{}", eventId, desc.length() == 0 ? " (empty)" : desc);

        if (eventId == EVENT_BATTERY_LEVEL && status >= 0 && status <= 100) {
            final GBDeviceEventBatteryInfo battery = new GBDeviceEventBatteryInfo();
            battery.level = (int) status;
            handleGBDeviceEvent(battery);
            return;
        }

        if (eventId == EVENT_LIVE_STEPS && inner != null) {
            long liveSteps = -1;
            for (final XWatchProtocol.Field x : XWatchProtocol.parse(inner)) {
                if (x.isVarint() && x.number == 1) {
                    liveSteps = x.value;
                    break;
                }
            }
            if (liveSteps >= 0 && liveSteps <= 250_000) {
                saveLiveSteps((int) liveSteps, System.currentTimeMillis());
            }
            return;
        }

        if (eventId == EVENT_BLOOD_OXYGEN_TEST && inner != null) {
            try {
                long testStatus = -1;
                byte[] result = null;
                for (final XWatchProtocol.Field x : XWatchProtocol.parse(inner)) {
                    if (x.number == 1 && x.isVarint()) {
                        testStatus = x.value;
                    } else if (x.number == 2 && !x.isVarint()) {
                        result = x.data;
                    }
                }
                if (testStatus == 1 && result != null) {
                    long time = -1;
                    long percent = -1;
                    for (final XWatchProtocol.Field y : XWatchProtocol.parse(result)) {
                        if (y.number == 1 && y.isVarint()) {
                            time = y.value;
                        } else if (y.number == 2 && y.isVarint()) {
                            percent = y.value;
                        }
                    }
                    if (percent >= 50 && percent <= 100 && time > 0 && time != lastSpo2Timestamp) {
                        lastSpo2Timestamp = time;
                        saveSpo2((int) percent, time * 1000L);
                    }
                }
            } catch (final RuntimeException e) {
                LOG.warn("could not read blood oxygen event", e);
            }
            return;
        }

        final long now = System.currentTimeMillis();
        if (eventId == EVENT_HEART_RATE_TEST && status == 1 && value >= 30 && value <= 250) {
            if (value != lastHeartRateValue || now - lastHeartRateMillis > DUPLICATE_WINDOW_MS) {
                saveManualHeartRate((int) value, now);
            }
            lastHeartRateValue = value;
            lastHeartRateMillis = now;
            return;
        }

        if (eventId == EVENT_STRESS_TEST && status == 1 && value >= 0 && value <= 100) {
            if (value != lastStressValue || now - lastStressMillis > DUPLICATE_WINDOW_MS) {
                saveStress((int) value, now);
            }
            lastStressValue = value;
            lastStressMillis = now;
            return;
        }

        LOG.debug("unhandled event id={} payload={}", eventId, GB.hexdump(f.payload));
    }

    // ---------------------------------------------------------------- storage

    private void saveManualHeartRate(final int bpm, final long timestampMillis) {
        try (DBHandler handler = GBApplication.acquireDB()) {
            final InfinixXWatchHeartRateSampleProvider provider =
                    new InfinixXWatchHeartRateSampleProvider(getDevice(), handler.getDaoSession());
            final InfinixXWatchHeartRateSample sample = new InfinixXWatchHeartRateSample();
            sample.setTimestamp(timestampMillis);
            sample.setHeartRate(bpm);
            final List<InfinixXWatchHeartRateSample> list = new ArrayList<>();
            list.add(sample);
            provider.persistSamples(list, getContext());
            LOG.info("saved manual heart rate {} bpm", bpm);
        } catch (final Exception e) {
            LOG.error("failed to save manual heart rate", e);
        }
    }

    private void saveSpo2(final int percent, final long timestampMillis) {
        try (DBHandler handler = GBApplication.acquireDB()) {
            final InfinixXWatchSpo2SampleProvider provider =
                    new InfinixXWatchSpo2SampleProvider(getDevice(), handler.getDaoSession());
            final InfinixXWatchSpo2Sample sample = new InfinixXWatchSpo2Sample();
            sample.setTimestamp(timestampMillis);
            sample.setSpo2(percent);
            final List<InfinixXWatchSpo2Sample> list = new ArrayList<>();
            list.add(sample);
            provider.persistSamples(list, getContext());
            LOG.info("saved blood oxygen {} %", percent);
        } catch (final Exception e) {
            LOG.error("failed to save blood oxygen", e);
        }
    }

    private void saveStress(final int level, final long timestampMillis) {
        try (DBHandler handler = GBApplication.acquireDB()) {
            final InfinixXWatchStressSampleProvider provider =
                    new InfinixXWatchStressSampleProvider(getDevice(), handler.getDaoSession());
            final InfinixXWatchStressSample sample = new InfinixXWatchStressSample();
            sample.setTimestamp(timestampMillis);
            sample.setStress(level);
            final List<InfinixXWatchStressSample> list = new ArrayList<>();
            list.add(sample);
            provider.persistSamples(list, getContext());
            LOG.info("saved stress level {}", level);
        } catch (final Exception e) {
            LOG.error("failed to save stress level", e);
        }
    }

    private void saveLiveSteps(final int stepsToday, final long nowMillis) {
        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            final long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            final long userId = DBHelper.getUser(session).getId();
            final InfinixXWatchActivitySampleDao dao = session.getInfinixXWatchActivitySampleDao();

            final int nowSec = (int) (nowMillis / 1000L);
            final Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(nowMillis);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            final int dayStartSec = (int) (cal.getTimeInMillis() / 1000L);

            final int bucketIndex = Math.max(0, Math.min(23, (nowSec - dayStartSec) / HOURLY_BUCKET_SECONDS));
            final int bucketTs = dayStartSec + bucketIndex * HOURLY_BUCKET_SECONDS;

            upsert(dao, deviceId, userId, bucketTs, stepsToday, null);
            LOG.info("saved live steps {} at bucket ts {}", stepsToday, bucketTs);
        } catch (final Exception e) {
            LOG.error("failed to save live steps", e);
        }
    }

    private void saveSteps(final List<XWatchProtocol.StepDay> days) {
        if (days.isEmpty()) {
            return;
        }
        final int now = (int) (System.currentTimeMillis() / 1000L);
        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            final long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            final long userId = DBHelper.getUser(session).getId();
            final InfinixXWatchActivitySampleDao dao = session.getInfinixXWatchActivitySampleDao();
            int saved = 0;
            for (final XWatchProtocol.StepDay d : days) {
                if (d.steps.isEmpty()) {
                    continue;
                }
                final int bucketSeconds = 86400 / d.steps.size();
                for (int i = 0; i < d.steps.size(); i++) {
                    final int ts = (int) d.date + i * bucketSeconds;
                    if (ts > now) {
                        break; // future bucket of today
                    }
                    upsert(dao, deviceId, userId, ts, d.steps.get(i), null);
                    saved++;
                }
            }
            LOG.info("saved {} step samples", saved);
        } catch (final Exception e) {
            LOG.error("failed to save steps", e);
        }
    }

    private void saveHeartRate(final List<XWatchProtocol.HeartRateDay> days) {
        if (days.isEmpty()) {
            return;
        }
        final int now = (int) (System.currentTimeMillis() / 1000L);
        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            final long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            final long userId = DBHelper.getUser(session).getId();
            final InfinixXWatchActivitySampleDao dao = session.getInfinixXWatchActivitySampleDao();
            int saved = 0;
            for (final XWatchProtocol.HeartRateDay d : days) {
                final int interval = d.intervalMinutes > 0 ? d.intervalMinutes : 5;
                for (int i = 0; i < d.readings.length; i++) {
                    final int bpm = d.readings[i] & 0xFF;
                    if (bpm == 0 || bpm > 220) {
                        continue; // 0 = no reading; larger values are not plausible heart rates
                    }
                    final int ts = (int) d.date + i * interval * 60;
                    if (ts > now) {
                        break;
                    }
                    upsert(dao, deviceId, userId, ts, null, bpm);
                    saved++;
                }
            }
            LOG.info("saved {} heart rate samples", saved);
        } catch (final Exception e) {
            LOG.error("failed to save heart rate", e);
        }
    }

    private static ActivityKind mapSleepKind(final XWatchProtocol.SleepSegment seg) {
        // TODO: Refine once state/category values are confirmed on full sleep captures.
        switch (seg.state) {
            case 1:
                return ActivityKind.LIGHT_SLEEP;
            case 2:
                return ActivityKind.DEEP_SLEEP;
            default:
                return ActivityKind.LIGHT_SLEEP;
        }
    }

    private void saveSleep(final List<XWatchProtocol.SleepDay> days) {
        if (days == null || days.isEmpty()) {
            return;
        }
        final int now = (int) (System.currentTimeMillis() / 1000L);
        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            final long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            final long userId = DBHelper.getUser(session).getId();
            final InfinixXWatchActivitySampleDao dao = session.getInfinixXWatchActivitySampleDao();
            int saved = 0;
            for (final XWatchProtocol.SleepDay d : days) {
                if (d.segments == null) {
                    continue;
                }
                for (final XWatchProtocol.SleepSegment s : d.segments) {
                    if (s.end <= s.start) {
                        continue;
                    }
                    final int kindCode = mapSleepKind(s).getCode();
                    final long endSec = Math.min(s.end, now);
                    for (long ts = s.start; ts < endSec; ts += SLEEP_STEP_SECONDS) {
                        upsertSleep(dao, deviceId, userId, (int) ts, kindCode);
                        saved++;
                    }
                }
            }
            LOG.info("saved {} sleep samples", saved);
        } catch (final Exception e) {
            LOG.error("failed to save sleep", e);
        }
    }

    private static void upsertSleep(final InfinixXWatchActivitySampleDao dao,
                                    final long deviceId,
                                    final long userId,
                                    final int timestamp,
                                    final int kindCode) {
        InfinixXWatchActivitySample sample = dao.queryBuilder()
                .where(InfinixXWatchActivitySampleDao.Properties.Timestamp.eq(timestamp),
                        InfinixXWatchActivitySampleDao.Properties.DeviceId.eq(deviceId))
                .unique();
        if (sample == null) {
            sample = new InfinixXWatchActivitySample();
            sample.setTimestamp(timestamp);
            sample.setDeviceId(deviceId);
            sample.setUserId(userId);
            sample.setSteps(ActivitySample.NOT_MEASURED);
            sample.setHeartRate(ActivitySample.NOT_MEASURED);
        }
        sample.setRawKind(kindCode);
        dao.insertOrReplace(sample);
    }

    /** Steps and heart rate can land on the same timestamp, so update an existing row instead of replacing it. */
    private static void upsert(final InfinixXWatchActivitySampleDao dao, final long deviceId, final long userId,
                               final int timestamp, final Integer steps, final Integer heartRate) {
        InfinixXWatchActivitySample sample = dao.queryBuilder()
                .where(InfinixXWatchActivitySampleDao.Properties.Timestamp.eq(timestamp),
                        InfinixXWatchActivitySampleDao.Properties.DeviceId.eq(deviceId))
                .unique();
        if (sample == null) {
            sample = new InfinixXWatchActivitySample();
            sample.setTimestamp(timestamp);
            sample.setDeviceId(deviceId);
            sample.setUserId(userId);
            sample.setRawKind(ActivityKind.NOT_MEASURED.getCode());
            sample.setSteps(ActivitySample.NOT_MEASURED);
            sample.setHeartRate(ActivitySample.NOT_MEASURED);
        }
        if (steps != null) {
            sample.setSteps(steps);
        }
        if (heartRate != null) {
            sample.setHeartRate(heartRate);
        }
        dao.insertOrReplace(sample);
    }

    // ---------------------------------------------------------------- watch-face install
    //
    // Sequence copied from a full capture of the vendor app: a start message (file name, size, type), then 4 KB
    // blocks. After the start message and after every block the watch confirms with a 0x92 response; the next block
    // is only sent after that. A shorter last block ends the transfer. Safeguards: only files that start with the
    // WFB marker and whose header size matches are sent, and a missing confirmation aborts the transfer.

    private static final long ACK_TIMEOUT_MS = 15_000L;

    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable ackTimeout = new Runnable() {
        @Override
        public void run() {
            abortFaceTransfer("the watch did not confirm in time");
        }
    };
    private byte[] faceData;
    private int faceOffset;
    private int awaitedAck = -1;

    @Override
    public void onInstallApp(final Uri uri, @NonNull final Bundle options) {
        final byte[] data;
        try (InputStream in = UriHelper.get(uri, getContext()).openInputStream()) {
            data = readFully(in);
        } catch (final IOException e) {
            LOG.error("could not read {}", uri, e);
            return;
        }
        final String problem = XWatchProtocol.checkWatchFaceFile(data);
        if (problem != null) {
            LOG.error("refusing to send {}: {}", uri, problem);
            return;
        }
        startFaceTransfer(faceFileName(uri, data), data);
    }

    private synchronized void startFaceTransfer(final String name, final byte[] data) {
        if (faceData != null) {
            LOG.warn("a watch-face transfer is already running");
            return;
        }
        faceData = data;
        faceOffset = 0;
        awaitedAck = XWatchProtocol.CMD_FILE_START;
        LOG.info("starting watch-face transfer: name {} size {}", name, data.length);
        sendFrame("face start", XWatchProtocol.fileStart(name, data.length, XWatchProtocol.FILE_TYPE_DOWNLOADED_FACE));
        armTimeout();
    }

    private synchronized void onFileTransferAck(final long eventId, final long code) {
        if (faceData == null || eventId != awaitedAck) {
            return;
        }
        timeoutHandler.removeCallbacks(ackTimeout);
        if (code != 0) {
            abortFaceTransfer("the watch refused with code " + code);
            return;
        }
        if (eventId == XWatchProtocol.CMD_FILE_DATA && faceOffset >= faceData.length) {
            LOG.info("watch-face transfer complete ({} bytes)", faceData.length);
            resetFaceTransfer();
            // read-only: shows which face is active now
            sendFrame("face status", XWatchProtocol.buildFrame(XWatchProtocol.CMD_DIAL_QUERY, new byte[0]));
            return;
        }
        sendNextFaceBlock();
    }

    private void sendNextFaceBlock() {
        final List<byte[]> frames = XWatchProtocol.faceBlockFrames(faceData, faceOffset);
        final TransactionBuilder b = createTransactionBuilder("face block at " + faceOffset);
        for (final byte[] frame : frames) {
            b.write(XWatchProtocol.CHAR_COMMAND, frame);
        }
        b.queue();
        faceOffset += Math.min(XWatchProtocol.FILE_BLOCK_SIZE, faceData.length - faceOffset);
        awaitedAck = XWatchProtocol.CMD_FILE_DATA;
        LOG.info("sent {} of {} bytes", faceOffset, faceData.length);
        armTimeout();
    }

    private void armTimeout() {
        timeoutHandler.removeCallbacks(ackTimeout);
        timeoutHandler.postDelayed(ackTimeout, ACK_TIMEOUT_MS);
    }

    private synchronized void abortFaceTransfer(final String reason) {
        LOG.error("watch-face transfer aborted at {} of {} bytes: {}", faceOffset,
                faceData == null ? 0 : faceData.length, reason);
        resetFaceTransfer();
    }

    private synchronized void resetFaceTransfer() {
        timeoutHandler.removeCallbacks(ackTimeout);
        faceData = null;
        faceOffset = 0;
        awaitedAck = -1;
    }

    private static byte[] readFully(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > XWatchProtocol.MAX_FACE_FILE_SIZE) {
                break;
            }
        }
        return out.toByteArray();
    }

    /** Uses the file's own name if it looks like the store's (32 hex characters + .wf), otherwise the MD5 of the contents. */
    private static String faceFileName(final Uri uri, final byte[] data) {
        String last = uri.getLastPathSegment();
        if (last != null) {
            last = last.substring(Math.max(last.lastIndexOf('/'), last.lastIndexOf(':')) + 1);
            // the store's name is 32 hex characters + .wf; a copy renamed to .fw (to get past the file manager) keeps it
            if (last.matches("[0-9a-fA-F]{32}\\.(wf|fw)")) {
                return last.substring(0, 32).toLowerCase(Locale.ROOT) + ".wf";
            }
        }
        try {
            final byte[] md5 = MessageDigest.getInstance("MD5").digest(data);
            final StringBuilder sb = new StringBuilder();
            for (final byte b : md5) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.append(".wf").toString();
        } catch (final NoSuchAlgorithmException e) {
            return "face.wf";
        }
    }

    // ---------------------------------------------------------------- DeviceSupport boilerplate

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    public boolean getImplicitCallbackModify() {
        return false;
    }

    @Override
    public boolean getSendWriteRequestResponse() {
        return false;
    }
}
