package com.example.ticketing;

import java.time.*;

class MutableClock extends Clock {
  private Instant now = Instant.parse("2026-10-23T12:00:00Z");

  void advance(Duration duration) {
    now = now.plus(duration);
  }

  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  public Clock withZone(ZoneId zone) {
    return this;
  }

  public Instant instant() {
    return now;
  }
}
