package com.instagram.insfav.fetcher;

/**
 * Deterministic clock: production delay logic runs without depending on Robolectric's frozen wall
 * clock.
 */
final class TestTime implements FetchBroker.Time {
  private final java.util.concurrent.atomic.AtomicLong now =
      new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

  public long now() {
    return now.get();
  }

  public void sleep(long millis) {
    now.addAndGet(millis);
    Thread.yield();
  }
}
