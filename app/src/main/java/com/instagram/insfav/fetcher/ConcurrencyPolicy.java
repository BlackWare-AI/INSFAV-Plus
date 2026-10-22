package com.instagram.insfav.fetcher;
import android.app.ActivityManager;
import android.content.Context;
/** Browser renderers use separate processes: Java memoryClass/32 is not a renderer budget. */
final class ConcurrencyPolicy {
 static int validate(int value){if(value<1||value>30)throw new IllegalArgumentException("CONCURRENCY_RANGE");return value;}
 static int budget(int requested,long available,long threshold,boolean lowMemory,long javaHeadroom){
  validate(requested);
  // Admission estimates, not measured per-page allocations. Runtime trim/renderer failures reduce the ceiling.
  long reserve=Math.max(threshold,256L*1024*1024);
  int system=(int)Math.min(30,Math.max(1,(available-reserve)/(64L*1024*1024)));
  int heap=(int)Math.min(30,Math.max(1,javaHeadroom/(12L*1024*1024)));
  return Math.max(1,Math.min(requested,Math.min(lowMemory?1:system,heap)));
 }
 static int initial(Context c,int requested){ActivityManager m=c.getSystemService(ActivityManager.class);ActivityManager.MemoryInfo i=new ActivityManager.MemoryInfo();m.getMemoryInfo(i);Runtime r=Runtime.getRuntime();return budget(requested,i.availMem,i.threshold,i.lowMemory,r.maxMemory()-(r.totalMemory()-r.freeMemory()));}
 static int reduced(int current){return Math.max(1,current/2);}
 private ConcurrencyPolicy(){}
}
