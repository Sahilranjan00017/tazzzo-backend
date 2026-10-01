package com.tazzzo.membership;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** Every {@code instant()} call returns a DIFFERENT millisecond: any attempt that wrote its own "now" is visible. */
final class TickingClock extends Clock {

    private final Instant base;
    private final AtomicLong ticks = new AtomicLong();

    TickingClock(Instant base) {
        this.base = base;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return base.plusMillis(ticks.incrementAndGet());
    }
}
