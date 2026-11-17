/*  Infinix XWatch N5 Pro support for Gadgetbridge (experimental).

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.devices.infinix;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import nodomain.freeyourgadget.gadgetbridge.activities.install.FwAppInstallerActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.install.InstallActivity;
import nodomain.freeyourgadget.gadgetbridge.devices.InstallHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.infinix.XWatchProtocol;
import nodomain.freeyourgadget.gadgetbridge.util.UriHelper;

/** Accepts a watch-face file (.wf, starts with "WFB") and offers it for installation on the watch. */
public class InfinixXWatchInstallHandler implements InstallHandler {
    private static final Logger LOG = LoggerFactory.getLogger(InfinixXWatchInstallHandler.class);

    private final String problem;

    public InfinixXWatchInstallHandler(final Uri uri, final Context context) {
        String found = "could not read the file";
        try {
            final UriHelper helper = UriHelper.get(uri, context);
            try (InputStream in = helper.openInputStream()) {
                final byte[] data = readAll(in, XWatchProtocol.MAX_FACE_FILE_SIZE + 1);
                found = XWatchProtocol.checkWatchFaceFile(data);
            }
        } catch (final IOException e) {
            LOG.warn("Failed to read {}", uri, e);
        }
        problem = found;
        if (problem != null) {
            LOG.debug("{} is not an N5 Pro watch face: {}", uri, problem);
        }
    }

    private static byte[] readAll(final InputStream in, final int limit) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > limit) {
                break;
            }
        }
        return out.toByteArray();
    }

    @NonNull
    @Override
    public Class<? extends Activity> getInstallActivity() {
        return FwAppInstallerActivity.class;
    }

    @Override
    public boolean isValid() {
        return problem == null;
    }

    @Override
    public void validateInstallation(@NonNull final InstallActivity installActivity, @NonNull final GBDevice device) {
        installActivity.setInstallEnabled(isValid());
    }

    @Override
    public void onStartInstall(@NonNull final GBDevice device) {
        // the transfer itself is started by InfinixXWatchSupport.onInstallApp
    }
}
