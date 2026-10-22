package com.instagram.insfav;
import android.content.*;
import android.net.Uri;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.io.*;
import java.nio.file.*;
import static org.junit.Assert.*;
/** Identical anonymous provider and loop on both source commits; JVM timings, not phone storage. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ThumbnailPipelineBenchmarkTest {
 @Test public void measureOriginalByteServingAndPrivateStorage()throws Exception {
  LocalImagesTest harness=new LocalImagesTest();harness.setup();
  Context c=ReflectionHelpers.getField(harness,"context");LocalImages images=ReflectionHelpers.getField(harness,"images");Store store=ReflectionHelpers.getField(harness,"store");LocalImagesTest.FixtureProvider provider=ReflectionHelpers.getField(harness,"provider");
  for(int i=0;i<32;i++)provider.add("root/synthetic"+i+".webp","synthetic"+i+".webp",256,256);
  images.select(Uri.parse("content://insfav.test/tree/root"),Intent.FLAG_GRANT_READ_URI_PERMISSION);images.scan((e,v)->{});
  int opens=provider.opens,queries=provider.childQueries;long started=System.nanoTime(),bytes=0;boolean identical=true;
  for(int round=0;round<2;round++)for(int i=0;i<32;i++) {byte[] actual=images.image("synthetic"+i,false);assertTrue(actual.length>0);bytes+=actual.length;identical&=java.util.Arrays.equals(actual,Files.readAllBytes(provider.files.get("root/synthetic"+i+".webp").toPath()));}
  long elapsed=System.nanoTime()-started;assertEquals(queries,provider.childQueries);
  System.out.println("THUMBNAIL_BENCHMARK {\"environment\":\"Robolectric API34 / synthetic 32 x 256 WebP, two passes\",\"requests\":64,\"sourceOpens\":"+(provider.opens-opens)+",\"directoryQueriesDuringReads\":"+(provider.childQueries-queries)+",\"elapsedMs\":"+elapsed/1000000.0+",\"responseBytes\":"+bytes+",\"originalBytesUnchanged\":"+identical+",\"derivedCacheBytes\":"+treeSize(new File(c.getCacheDir(),"local-thumbnails"))+",\"indexBytes\":"+new File(store.root,"thumbnail-index.json").length()+",\"privateFilesBytes\":"+treeSize(store.root)+",\"privateCacheBytes\":"+treeSize(c.getCacheDir())+",\"sourceFolderBytes\":"+treeSize(provider.directory)+"}");
 }
 static long treeSize(File f){if(f.isFile())return f.length();long n=0;File[] entries=f.listFiles();if(entries!=null)for(File e:entries)n+=treeSize(e);return n;}
}
