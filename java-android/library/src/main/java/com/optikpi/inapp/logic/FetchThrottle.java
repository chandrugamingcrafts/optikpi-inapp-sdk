package com.optikpi.inapp.logic;

import java.time.Duration;
import java.time.Instant;

public final class FetchThrottle {
  public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(300);
  public static final int MIN_INTERVAL_SEC = 30;
  public static final int MAX_INTERVAL_SEC = 24 * 60 * 60;

  private Instant lastResponseAt;
  private Duration interval = DEFAULT_INTERVAL;

  public Duration getInterval() {
    return interval;
  }

  public boolean canFetch(Instant now) {
    return lastResponseAt == null || Duration.between(lastResponseAt, now).compareTo(interval) >= 0;
  }

  public void recordResponse(Instant at, Integer nextCheckAfterSec) {
    lastResponseAt = at;
    if (nextCheckAfterSec == null) {
      interval = DEFAULT_INTERVAL;
    } else {
      int clamped = Math.max(MIN_INTERVAL_SEC, Math.min(MAX_INTERVAL_SEC, nextCheckAfterSec));
      interval = Duration.ofSeconds(clamped);
    }
  }
}