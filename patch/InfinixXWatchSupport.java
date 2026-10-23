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
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * First milestone: connect, read firmware version and battery, set the time,
 * and log (not store) the answers to "today" history queries so the decoders
 * can be checked on a live watch.
 */
public class InfinixXWatchSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(InfinixXWatchSupport.class);

    private final Map<UUID, XWatchProtocol.Reassembler> reassemblers = new HashMap<>();

    public InfinixXWatchSupport() {
        super(LOG);
        addSupportedService(XWatchProtocol.SERVICE);
        reassemblers.put(XWatchProtocol.CHAR_COMMAND, new XWatchProtocol.Reassembler());
        reassemblers.put(XWatchProtocol.CHAR_SECONDARY, new XWatchProtocol.Reassembler());
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);

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

        builder.notify(XWatchProtocol.CHAR_COMMAND, true);
        builder.notify(XWatchProtocol.CHAR_SECONDARY, true);

        // Avoids a database error if the first save happens before the version reply arrives.
        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");

        final long nowMillis = System.currentTimeMillis();
        // Hypothesis from one capture: timezone value = offset in hours * 100 (300 for UTC+3).
        // getOffset() returns milliseconds, so: ms -> hours -> *100.
        final int tzValue = (int) (TimeZone.getDefault().getOffset(nowMillis) / 3_600_000L * 100L);

        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.versionRequest());
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.batteryRequest());
        builder.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.timeSync(nowMillis / 1000, tzValue));
        // Read-only queries, logged only: step, heart-rate and sleep data for "today".
        builder.write(XWatchProtocol.CHAR_COMMAND,
                XWatchProtocol.historyRequest(XWatchProtocol.CMD_STEPS, XWatchProtocol.QUERY_TODAY));
        builder.write(XWatchProtocol.CHAR_COMMAND,
                XWatchProtocol.historyRequest(XWatchProtocol.CMD_HEART_RATE, XWatchProtocol.QUERY_TODAY));
        builder.write(XWatchProtocol.CHAR_COMMAND,
                XWatchProtocol.historyRequest(XWatchProtocol.CMD_SLEEP, XWatchProtocol.QUERY_TODAY));

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
                handleFrame(frame);
            } catch (final RuntimeException e) {
                LOG.error("failed to handle frame cmd 0x{}", Integer.toHexString(frame.cmd), e);
            }
        }
        return true;
    }

    private void handleFrame(final XWatchProtocol.Frame f) {
        LOG.debug("frame cmd=0x{} flags=0x{} idx={} len={}", Integer.toHexString(f.cmd),
                Integer.toHexString(f.flags), f.idx, f.payload.length);
        switch (f.cmd) {
            case XWatchProtocol.CMD_VERSION: {
                final String version = XWatchProtocol.decodeVersion(f.payload);
                if (version != null) {
                    final GBDeviceEventVersionInfo event = new GBDeviceEventVersionInfo();
                    event.fwVersion = version;
                    handleGBDeviceEvent(event);
                }
                break;
            }
            case XWatchProtocol.CMD_BATTERY: {
                final int level = XWatchProtocol.decodeBattery(f.payload);
                if (level >= 0 && level <= 100) {
                    final GBDeviceEventBatteryInfo event = new GBDeviceEventBatteryInfo();
                    event.level = level;
                    handleGBDeviceEvent(event);
                }
                break;
            }
            case XWatchProtocol.CMD_STEPS:
                for (final XWatchProtocol.StepDay d : XWatchProtocol.decodeSteps(f.payload)) {
                    LOG.info("steps {}: total {} in {} buckets", new Date(d.date * 1000), d.total(), d.steps.size());
                }
                break;
            case XWatchProtocol.CMD_HEART_RATE:
                for (final XWatchProtocol.HeartRateDay d : XWatchProtocol.decodeHeartRate(f.payload)) {
                    int nonZero = 0;
                    for (final byte b : d.readings) {
                        if (b != 0) nonZero++;
                    }
                    LOG.info("heart rate {}: {} slots, {} non-zero", new Date(d.date * 1000), d.readings.length, nonZero);
                }
                break;
            case XWatchProtocol.CMD_SLEEP:
                for (final XWatchProtocol.SleepDay d : XWatchProtocol.decodeSleep(f.payload)) {
                    for (final XWatchProtocol.SleepSegment s : d.segments) {
                        LOG.info("sleep {} -> {}: {} min state {} category {}", new Date(s.start * 1000),
                                new Date(s.end * 1000), s.minutes, s.state, s.category);
                    }
                }
                break;
            default:
                LOG.debug("cmd 0x{} payload {}", Integer.toHexString(f.cmd), GB.hexdump(f.payload));
        }
    }

    // ---------------------------------------------------------------- DeviceSupport boilerplate

    /**
     * Required by DeviceSupport. Returning true lets Gadgetbridge proactively reconnect
     * (on Bluetooth on, app start, etc.). Change to false if you only ever want to
     * connect by tapping the device card.
     */
    @Override
    public boolean useAutoConnect() {
        return true;
    }

    /**
     * The XWatch command characteristic is write-without-response, so we do not need the
     * implicit "modify" callback. Returning false matches how the device behaves.
     */
    @Override
    public boolean getImplicitCallbackModify() {
        return false;
    }

    /**
     * Write-Without-Response means there is no GATT-level confirmation, so do not ask
     * Gadgetbridge to wait for a write-request response.
     */
    @Override
    public boolean getSendWriteRequestResponse() {
        return false;
    }
}
