/*  Infinix XWatch N5 Pro support for Gadgetbridge (experimental).

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.infinix;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.InfinixXWatchHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.InfinixXWatchStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.InfinixXWatchActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.InfinixXWatchActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.InfinixXWatchHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.InfinixXWatchStressSample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

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
        builder.notify(XWatchProtocol.CHAR_COMMAND, true);
        builder.notify(XWatchProtocol.CHAR_SECONDARY, true);
        builder.sleep(NOTIFY_SETTLE_MS);
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.versionRequest());
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.batteryRequest());
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.timeSync(nowMillis / 1000, tzValue));
        // Read-only queries for "today" and "history". The watch answers each with an index; the
        // checkpoint reply below makes it send the records, which are then stored for the dashboard.
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_STEPS, XWatchProtocol.QUERY_TODAY));
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_HEART_RATE, XWatchProtocol.QUERY_TODAY));
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_SLEEP, XWatchProtocol.QUERY_TODAY));
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_STEPS, XWatchProtocol.QUERY_HISTORY));
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_HEART_RATE, XWatchProtocol.QUERY_HISTORY));
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_SLEEP, XWatchProtocol.QUERY_HISTORY));
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
                break;
            }
            case XWatchProtocol.CMD_STEPS: {
                final List<XWatchProtocol.StepDay> days = XWatchProtocol.decodeSteps(f.payload);
                for (final XWatchProtocol.StepDay d : days) {
                    LOG.info("steps {}: total {} in {} buckets", new Date(d.date * 1000), d.total(), d.steps.size());
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
                    LOG.info("heart rate {}: {} slots, {} non-zero", new Date(d.date * 1000), d.readings.length, nonZero);
                }
                saveHeartRate(days);
                break;
            }
            case XWatchProtocol.CMD_SLEEP: {
                // Decoded and logged only; sleep storage comes later.
                for (final XWatchProtocol.SleepDay d : XWatchProtocol.decodeSleep(f.payload)) {
                    for (final XWatchProtocol.SleepSegment s : d.segments) {
                        LOG.info("sleep {} -> {}: {} min state {} category {}", new Date(s.start * 1000),
                                new Date(s.end * 1000), s.minutes, s.state, s.category);
                    }
                }
                break;
            }
            default:
                LOG.debug("cmd 0x{} payload {}", Integer.toHexString(f.cmd), GB.hexdump(f.payload));
        }
    }

    // ---------------------------------------------------------------- unsolicited events (cmd 0x91)

    private static final long EVENT_HEART_RATE_TEST = 2002;
    private static final long EVENT_STRESS_TEST = 2021;
    /** The watch sends each result twice a few seconds apart. */
    private static final long DUPLICATE_WINDOW_MS = 30_000L;

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

        final long now = System.currentTimeMillis();
        if (eventId == EVENT_HEART_RATE_TEST && status == 1 && value >= 30 && value <= 250) {
            if (value != lastHeartRateValue || now - lastHeartRateMillis > DUPLICATE_WINDOW_MS) {
                saveManualHeartRate((int) value, now);
            }
            lastHeartRateValue = value;
            lastHeartRateMillis = now;
        } else if (eventId == EVENT_STRESS_TEST && status == 1 && value >= 0 && value <= 100) {
            if (value != lastStressValue || now - lastStressMillis > DUPLICATE_WINDOW_MS) {
                saveStress((int) value, now);
            }
            lastStressValue = value;
            lastStressMillis = now;
        }
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

    private void saveSteps(final List<XWatchProtocol.StepDay> days) {
        if (days.isEmpty()) {
            return;
        }
        final int now = (int) (System.currentTimeMillis() / 1000);
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
        final int now = (int) (System.currentTimeMillis() / 1000);
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
