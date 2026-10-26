package com.instagram.insfav;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,manifest=org.robolectric.annotation.Config.NONE)
public class StatusTasksTest {
 @Test public void pendingReadsCoalesceButCommandsAndCriticalNotificationsStayOrdered() {
  ArrayDeque<Runnable> work=new ArrayDeque<>();StatusTasks tasks=new StatusTasks(work::add);List<String> delivered=new ArrayList<>();
  tasks.execute("status",()->delivered.add("old"));tasks.execute("status",()->delivered.add("latest"));
  // Commands and critical signals use the serial executor directly.
  work.add(()->delivered.add("pause"));work.add(()->delivered.add("stop"));
  tasks.execute("fetcher-status",()->delivered.add("fetcher"));
  assertEquals(4,work.size());while(!work.isEmpty())work.remove().run();
  assertEquals(Arrays.asList("latest","pause","stop","fetcher"),delivered);
 }
 @Test public void actualBridgeCommandsNeverUseTheStatusCoalescingKey() {
  ArrayDeque<Runnable> work=new ArrayDeque<>();StatusTasks tasks=new StatusTasks(work::add);List<String> delivered=new ArrayList<>();
  tasks.request("{\"command\":\"status\"}",()->delivered.add("old"));tasks.request("{\"command\":\"status\"}",()->delivered.add("latest"));
  tasks.request("{\"command\":\"fetcher-action\",\"action\":\"pause\"}",()->delivered.add("pause"));
  tasks.request("{\"command\":\"fetcher-action\",\"action\":\"stop\"}",()->delivered.add("stop"));
  assertEquals(3,work.size());while(!work.isEmpty())work.remove().run();assertEquals(Arrays.asList("latest","pause","stop"),delivered);
 }
}
