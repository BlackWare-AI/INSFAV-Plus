package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class StatusDeliveryBenchmarkTest {
  @Test
  public void measureBurstCoalescingAndCriticalImmediateDelivery() throws Exception {
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1));
    AtomicInteger delivered = new AtomicInteger();
    Fetcher.Listener listener =
        new Fetcher.Listener() {
          public void changed() {
            delivered.incrementAndGet();
          }

          public void fullReady(String user, String token, int width, int height) {}

          public void fullFailed(String token, String code) {}
        };
    Fetcher.addListener(listener);
    try {
      long began = System.nanoTime();
      for (int i = 0; i < 1000; i++) Fetcher.publish();
      long burst = System.nanoTime() - began;
      Thread.sleep(1150);
      int normal = delivered.get();
      assertTrue(normal > 0);
      delivered.set(0);
      long streamStart = System.nanoTime();
      int streamEvents = 0;
      while (System.nanoTime() - streamStart < 2200000000L) {
        Fetcher.publish();
        streamEvents++;
        Thread.sleep(20);
      }
      Thread.sleep(1100);
      int streamDeliveries = delivered.get(), before = delivered.get();
      boolean explicitCritical = false;
      try {
        java.lang.reflect.Method force = Fetcher.class.getDeclaredMethod("publish", boolean.class);
        force.setAccessible(true);
        force.invoke(null, true);
        force.invoke(null, true);
        explicitCritical = true;
      } catch (NoSuchMethodException old) {
        Fetcher.publish();
        Fetcher.publish();
      }
      boolean immediate = delivered.get() == before + 2;
      if (explicitCritical) assertTrue(immediate);
      System.out.println(
          "STATUS_DELIVERY "
              + new JSONObject()
                  .put(
                      "environment",
                      "Host Robolectric; synthetic 1000-event burst and 1150 ms drain")
                  .put("events", 1000)
                  .put("normalDeliveries", normal)
                  .put("burstCallNanos", burst)
                  .put("streamEvents", streamEvents)
                  .put("streamDurationMs", 2200)
                  .put("streamDrainMs", 1100)
                  .put("streamDeliveries", streamDeliveries)
                  .put("twoCriticalEventsDeliveredSynchronously", immediate));
    } finally {
      Fetcher.removeListener(listener);
    }
  }
}
