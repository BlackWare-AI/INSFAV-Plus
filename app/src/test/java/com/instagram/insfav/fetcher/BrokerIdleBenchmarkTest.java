package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Host thread CPU/wait transitions, not Android power or kernel wakeup certification. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class BrokerIdleBenchmarkTest {
  @Test
  public void measureIdleDispatcherWithoutOpeningAWebView() throws Exception {
    // Reflection keeps host-only java.management types out of the Android boot classpath.
    Object threads =
        Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean")
            .invoke(null);
    Class<?> bean = Class.forName("java.lang.management.ThreadMXBean"),
        infoType = Class.forName("java.lang.management.ThreadInfo");
    Method ids = bean.getMethod("getAllThreadIds"),
        info = bean.getMethod("getThreadInfo", long.class),
        cpuTime = bean.getMethod("getThreadCpuTime", long.class),
        waited = infoType.getMethod("getWaitedCount");
    long previous = 0;
    for (long id : (long[]) ids.invoke(threads)) previous = Math.max(previous, id);
    new FetchBroker(
        RuntimeEnvironment.getApplication(),
        full -> {
          throw new AssertionError("Idle factory invoked");
        });
    Thread.sleep(150);
    long target = 0;
    for (long id : (long[]) ids.invoke(threads)) {
      Object state = info.invoke(threads, id);
      if (id > previous
          && state != null
          && infoType.getMethod("getThreadName").invoke(state).equals("insfav-profile-dispatcher"))
        target = id;
    }
    assertTrue(target > 0);
    Object before = info.invoke(threads, target);
    boolean supported = (Boolean) bean.getMethod("isThreadCpuTimeSupported").invoke(threads);
    long cpu = supported ? (Long) cpuTime.invoke(threads, target) : -1, started = System.nanoTime();
    Thread.sleep(1000);
    Object after = info.invoke(threads, target);
    long elapsed = System.nanoTime() - started,
        used = cpu >= 0 ? (Long) cpuTime.invoke(threads, target) - cpu : -1;
    System.out.println(
        "BROKER_IDLE "
            + new JSONObject()
                .put("environment", "Host JVM + Robolectric; idle dispatcher")
                .put("sampleNanos", elapsed)
                .put("threadCpuNanos", used)
                .put("waitTransitions", (Long) waited.invoke(after) - (Long) waited.invoke(before))
                .put("cpuMeasured", used >= 0));
  }
}
