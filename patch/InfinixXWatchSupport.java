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

import java.util.HashMap;
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
 * The history commands (0x34 / 0x36 / 0x38) in XWatchProtocol are NOT
 * exercised here, because the capture never shows the official app sending
 * them. Their decoders stay in XWatchProtocol unused until we learn the real
 * command bytes from a capture that contains an actual sync.
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
    protected TransactionBuilder initializeDevice(final TransactionBuilder ignored) {
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

        // --- Transaction 1: subscribe and mark initialising. -----------------
        // The descriptor write from notify() must complete before the watch
        // will deliver a command reply, so this is queued separately.
        TransactionBuilder b1 = createTransactionBuilder("xwatch subscribe");
        b1.setDeviceState(GBDevice.State.INITIALIZING);
        b1.notify(XWatchProtocol.CHAR_COMMAND, true);
        b1.notify(XWatchProtocol.CHAR_SECONDARY, true);
        b1.queue();

        // --- Transaction 2: give the descriptors time, then ask. ------------
        final long nowMillis = System.currentTimeMillis();
        // Hypothesis from the capture: timezone value = offset in hours * 100.
        final int tzValue = (int) (TimeZone.getDefault().getOffset(nowMillis) / 3_600_000L * 100L);

        TransactionBuilder b2 = createTransactionBuilder("xwatch init");
        b2.sleep(NOTIFY_SETTLE_MS);
        b2.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.versionRequest());
        b2.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.batteryRequest());
        b2.write(XWatchProtocol.CHAR_COMMAND, XWatchProtocol.timeSync(nowMillis / 1000, tzValue));
        b2.setDeviceState(GBDevice.State.INITIALIZED);
        return b2;
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

    private void handleFrame(final UUID source, final XWatchProtocol.Frame f) {
        LOG.debug("{} frame cmd=0x{} flags=0x{} idx={} len={}", source,
                Integer.toHexString(f.cmd), Integer.toHexString(f.flags), f.idx, f.payload.length);
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
