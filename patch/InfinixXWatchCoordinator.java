/*  Infinix XWatch N5 Pro support for Gadgetbridge (experimental).

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.devices.infinix;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.InfinixXWatchHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.InfinixXWatchStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.InfinixXWatchActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.InfinixXWatchHeartRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.InfinixXWatchStressSampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.StressSample;
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

    // ---------------------------------------------------------------- what the watch can do (dashboard)

    @Override
    public boolean supportsDataFetching(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsActivityTracking(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsHeartRateMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsStressMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    /** Sleep is decoded but not stored yet. */
    @Override
    public boolean supportsSleepMeasurement(@NonNull final GBDevice device) {
        return false;
    }

    // ---------------------------------------------------------------- sample providers

    @Override
    public SampleProvider<? extends ActivitySample> getSampleProvider(final GBDevice device, final DaoSession session) {
        return new InfinixXWatchActivitySampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends StressSample> getStressSampleProvider(@NonNull final GBDevice device, @NonNull final DaoSession session) {
        return new InfinixXWatchStressSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends HeartRateSample> getHeartRateManualSampleProvider(@NonNull final GBDevice device, @NonNull final DaoSession session) {
        return new InfinixXWatchHeartRateSampleProvider(device, session);
    }

    /** Lets Gadgetbridge delete this device's samples when the device is removed. */
    @Override
    public Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        final Map<AbstractDao<?, ?>, Property> map = new HashMap<>(3);
        map.put(session.getInfinixXWatchActivitySampleDao(), InfinixXWatchActivitySampleDao.Properties.DeviceId);
        map.put(session.getInfinixXWatchHeartRateSampleDao(), InfinixXWatchHeartRateSampleDao.Properties.DeviceId);
        map.put(session.getInfinixXWatchStressSampleDao(), InfinixXWatchStressSampleDao.Properties.DeviceId);
        return map;
    }
}
