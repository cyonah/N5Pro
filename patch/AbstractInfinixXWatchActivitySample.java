/*  Infinix XWatch N5 Pro support for Gadgetbridge (experimental).

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.entities;

/** Base class of the generated InfinixXWatchActivitySample entity (steps + heart rate). */
public abstract class AbstractInfinixXWatchActivitySample extends AbstractActivitySample {
    /** The watch reports no intensity, so the step count of the sample is used for the chart bars. */
    @Override
    public int getRawIntensity() {
        final int steps = getSteps();
        return steps > 0 ? steps : 0;
    }
}
