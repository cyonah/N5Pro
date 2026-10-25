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

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

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
        // Read-only queries for "today". Answers are decoded and written to the log so the decoders
        // can be checked on a live watch before anything is stored in the database.
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_STEPS, XWatchProtocol.QUERY_TODAY));
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_HEART_RATE, XWatchProtocol.QUERY_TODAY));
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.historyRequest(XWatchProtocol.CMD_SLEEP, XWatchProtocol.QUERY_TODAY));
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
            case XWatchProtocol.CMD_BIND_REPLY: {
                // Unsolicited events: field 1 = event id, field 2 = a small message whose field 1 is usually the value.
                long eventId = -1;
                byte[] inner = null;
                for (final XWatchProtocol.Field fld : XWatchProtocol.parse(f.payload)) {
                    if (fld.number == 1 && fld.isVarint()) {
                        eventId = fld.value;
                    } else if (fld.number == 2 && !fld.isVarint()) {
                        inner = fld.data;
                    }
                }
                final StringBuilder desc = new StringBuilder();
                if (inner != null) {
                    try {
                        for (final XWatchProtocol.Field x : XWatchProtocol.parse(inner)) {
                            desc.append(" f").append(x.number).append('=')
                                    .append(x.isVarint() ? Long.toString(x.value) : "bytes" + x.data.length);
                        }
                    } catch (final RuntimeException ignored) {
                        desc.append(" (not a plain message, ").append(inner.length).append(" bytes)");
                    }
                }
                LOG.info("event {}:{}", eventId, desc.length() == 0 ? " (empty)" : desc);
                break;
            }
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
                for (final XWatchProtocol.IndexEntry e : XWatchProtocol.decodeIndex(f.cmd, f.payload)) {
                    LOG.info("steps index {}: a={} b={}", new Date(e.date * 1000), e.a, e.b);
                }
                for (final XWatchProtocol.StepDay d : XWatchProtocol.decodeSteps(f.payload)) {
                    LOG.info("steps {}: total {} in {} buckets", new Date(d.date * 1000), d.total(), d.steps.size());
                }
                break;
            }
            case XWatchProtocol.CMD_HEART_RATE: {
                for (final XWatchProtocol.IndexEntry e : XWatchProtocol.decodeIndex(f.cmd, f.payload)) {
                    LOG.info("heart rate index {}: a={} b={}", new Date(e.date * 1000), e.a, e.b);
                }
                for (final XWatchProtocol.HeartRateDay d : XWatchProtocol.decodeHeartRate(f.payload)) {
                    int nonZero = 0;
                    for (final byte b : d.readings) {
                        if (b != 0) nonZero++;
                    }
                    LOG.info("heart rate {}: {} slots, {} non-zero", new Date(d.date * 1000), d.readings.length, nonZero);
                }
                break;
            }
            case XWatchProtocol.CMD_SLEEP: {
                for (final XWatchProtocol.IndexEntry e : XWatchProtocol.decodeIndex(f.cmd, f.payload)) {
                    LOG.info("sleep index {}: a={} b={}", new Date(e.date * 1000), e.a, e.b);
                }
                for (final XWatchProtocol.SleepDay d : XWatchProtocol.decodeSleep(f.payload)) {
                    for (final XWatchProtocol.SleepSegment s : d.segments) {
                        LOG.info("sleep {} -> {}: {} min state {} category {}", new Date(s.start * 1000),
                                new Date(s.end * 1000), s.minutes, s.state, s.category);
                    }
                }
                break;
            }
            default:
                // Includes the unsolicited 0x91 stream seen on CHAR_SECONDARY.
                LOG.debug("cmd 0x{} payload {}", Integer.toHexString(f.cmd), GB.hexdump(f.payload));
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
