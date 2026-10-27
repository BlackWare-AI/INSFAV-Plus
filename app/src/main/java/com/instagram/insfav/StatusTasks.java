package com.instagram.insfav;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Coalesce only replaceable status reads; commands stay on the original serial executor. */
final class StatusTasks {
  private final Executor executor;
  private final Map<String, Runnable> pending = new HashMap<>();
  StatusTasks(Executor executor) { this.executor = executor; }
  synchronized void execute(String key, Runnable task) {
    if (pending.put(key, task) != null) return;
    try {
      executor.execute(() -> {
        Runnable latest;
        synchronized (StatusTasks.this) { latest = pending.remove(key); }
        latest.run();
      });
    } catch (RejectedExecutionException closed) {
      pending.remove(key);
      throw closed;
    }
  }
  void request(String raw, Runnable task) {
    String command;
    try { command = new org.json.JSONObject(raw).optString("command"); }
    catch (org.json.JSONException invalid) { executor.execute(task); return; }
    if (command.equals("status") || command.equals("fetcher-status")) execute(command, task);
    else executor.execute(task);
  }
}
