package com.instagram.insfav;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class SourceBytesTest {
 @Test public void independentStreamsReturnOriginalEncodedBytesAcrossChunks()throws Exception{
  byte[] original=new byte[70003];new Random(7).nextBytes(original);SourceBytes source=SourceBytes.read(new ByteArrayInputStream(original));
  assertEquals(original.length,source.length);assertTrue(source.chunks.size()>1);
  try(InputStream a=source.open();InputStream b=source.open()){
   assertEquals(original[0]&255,a.read());assertEquals(original[0]&255,b.read());byte[] result=new byte[original.length];result[0]=original[0];int offset=1,n;
   while((n=a.read(result,offset,result.length-offset))>0)offset+=n;assertArrayEquals(original,result);assertEquals(-1,a.read());
  }
 }
 @Test public void exactByteLimitIsAcceptedAndOneExtraByteIsRejected()throws Exception{
  assertEquals(17,SourceBytes.read(new ByteArrayInputStream(new byte[17]),17).length);
  try{SourceBytes.read(new ByteArrayInputStream(new byte[18]),17);fail();}catch(IOException expected){}
 }
 @Test public void closingOneReaderDoesNotInvalidateAnotherReader()throws Exception{
  SourceBytes source=SourceBytes.read(new ByteArrayInputStream(new byte[]{3,4,5}));InputStream a=source.open(),b=source.open();a.close();assertEquals(-1,a.read());assertEquals(3,b.read());b.close();
 }
}
