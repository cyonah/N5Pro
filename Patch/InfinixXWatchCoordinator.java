/*  Infinix XWatch N5 Pro support for Gadgetbridge (experimental).

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.devices.infinix;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.infinix.InfinixXWatchSupport;

public class InfinixXWatchCoordinator extends AbstractBLEDeviceCoordinator {
    @Override
    protected Pattern getSupportedDeviceName() {
        // Advertised name seen in a GATT browser. The old "XWatch" driver matches
        // "XWatch.*" and is narrowed by apply_patch.py so it no longer claims this watch.
        return Pattern.compile("XWatch N5 Pro.*");
    }

    @Override
    public String getManufacturer() {
        return "Infinix";
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_infinix_xwatch_n5_pro;
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return InfinixXWatchSupport.class;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull final GBDevice device) {
        return DeviceKind.WATCH;
    }
}
