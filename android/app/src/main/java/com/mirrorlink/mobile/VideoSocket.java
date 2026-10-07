package com.mirrorlink.mobile;
import android.util.Base64;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A transient socket outage never recreates MediaProjection. Queues are bounded. */
final class VideoSocket implements AutoCloseable {
 private volatile Socket socket;private InputStream input;private OutputStream output;
 private final SecureRandom random=new SecureRandom();private final ArrayBlockingQueue<byte[]> packets=new ArrayBlockingQueue<>(3);
 private final Object writeLock=new Object();private final AtomicBoolean closed=new AtomicBoolean();
 private volatile boolean waitKey=true,online;private volatile long writingAt;private volatile String configuration;
 private volatile int configEpoch;private final String base,token,path;private final boolean video;
 private final Runnable requestKey;private final java.util.function.Consumer<Exception> failure;private final java.util.function.Consumer<JSONObject> messages;
 private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();private Thread manager,writer;
 VideoSocket(String base,String token,Runnable key,java.util.function.Consumer<Exception> failure)throws Exception{this(base,token,"/api/video",key,failure,d->{});}
 VideoSocket(String base,String token,String path,Runnable key,java.util.function.Consumer<Exception> failure,java.util.function.Consumer<JSONObject> messages)throws Exception{
  this.base=base;this.token=token;this.path=path;video=path.equals("/api/video");requestKey=key;this.failure=failure;this.messages=messages;
  try{open();}catch(Exception e){close();throw e;}manager=new Thread(this::manage,"Yinlian-Socket");manager.start();
  watchdog.scheduleAtFixedRate(()->{if(writingAt>0&&SystemClock.elapsedRealtime()-writingAt>1200)dropConnection();},500,500,TimeUnit.MILLISECONDS);
 }
 private void open()throws Exception{
  URI uri=new URI(base);Socket next=new Socket();socket=next;next.connect(new InetSocketAddress(uri.getHost(),uri.getPort()),2500);next.setTcpNoDelay(true);next.setSoTimeout(3000);
  input=new BufferedInputStream(next.getInputStream());output=new BufferedOutputStream(next.getOutputStream(),32768);
  byte[] nonce=new byte[16];random.nextBytes(nonce);String key=Base64.encodeToString(nonce,Base64.NO_WRAP);
  String req="GET "+path+" HTTP/1.1\r\nHost: "+uri.getHost()+":"+uri.getPort()+"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Protocol: yinlian-v1\r\nAuthorization: Bearer "+token+"\r\n\r\n";
  output.write(req.getBytes(StandardCharsets.US_ASCII));output.flush();ByteArrayOutputStream h=new ByteArrayOutputStream();int suffix=0;
  while(h.size()<8192){int b=input.read();if(b<0)throw new EOFException();h.write(b);suffix=(suffix<<8)|b;if(suffix==0x0d0a0d0a)break;}
  String response=h.toString("US-ASCII"),accept=Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)),Base64.NO_WRAP);
  if(response.startsWith("HTTP/1.1 403")||response.startsWith("HTTP/1.1 401"))throw new Api.Failure(401,"配对已结束");
  if(!response.startsWith("HTTP/1.1 101 ")||!response.contains("Sec-WebSocket-Accept: "+accept+"\r\n"))throw new IOException("推流连接暂不可用");
  next.setSoTimeout(12000);packets.clear();waitKey=true;online=true;requestKey.run();
 }
 synchronized void config(String text,int epoch){configuration=text;configEpoch=epoch;packets.clear();waitKey=true;}
 synchronized boolean offer(byte[] bytes,boolean key,int epoch){
  if(closed.get()||!online||video&&epoch!=configEpoch)return false;
  if(video&&waitKey&&!key)return false;if(video&&key&&waitKey){packets.clear();waitKey=false;}
  if(!packets.offer(bytes)){packets.clear();if(video){waitKey=true;requestKey.run();return false;}return packets.offer(bytes);}return true;
 }
 boolean connected(){return online&&!closed.get();}
 private void manage(){long outage=0;try{while(!closed.get()){
  try{if(!online)open();outage=0;writer=new Thread(this::writeLoop,"Yinlian-MediaSend");writer.start();readLoop();}
  catch(Exception e){dropConnection();if(writer!=null){writer.interrupt();writer.join(1600);}if(closed.get())break;if(e instanceof Api.Failure)throw e;
   if(outage==0)outage=SystemClock.elapsedRealtime();if(SystemClock.elapsedRealtime()-outage>10000)throw new IOException("网络中断超过 10 秒，请重新连接");Thread.sleep(600);
  }
 }}catch(Exception e){if(!closed.get()){close();failure.accept(e);}}}
 private void writeLoop(){int sentEpoch=0;try{while(!closed.get()&&online){byte[] data=packets.poll(200,TimeUnit.MILLISECONDS);if(data==null)continue;
  if(video){int epoch=java.nio.ByteBuffer.wrap(data,8,4).getInt();String text;synchronized(this){if(epoch!=configEpoch)continue;text=configuration;}writingAt=SystemClock.elapsedRealtime();if(sentEpoch!=epoch){send(1,text.getBytes(StandardCharsets.UTF_8));sentEpoch=epoch;}}
  else writingAt=SystemClock.elapsedRealtime();send(2,data);writingAt=0;
 }}catch(Exception e){dropConnection();}finally{writingAt=0;}}
 private void readLoop()throws Exception{while(!closed.get()&&online){int first=input.read(),second=input.read();if(first<0||second<0)throw new EOFException();int op=first&15;long length=second&127;
  if((first&0x80)==0||(second&0x80)!=0)throw new IOException("无效响应");if(length==126)length=((long)readByte()<<8)|readByte();else if(length==127){length=0;for(int i=0;i<8;i++)length=(length<<8)|readByte();}
  if(length<0||length>8192)throw new IOException("控制消息过大");byte[] bytes=new byte[(int)length];int n=0;while(n<bytes.length){int got=input.read(bytes,n,bytes.length-n);if(got<0)throw new EOFException();n+=got;}
  if(op==8)throw new EOFException();if(op==9){send(10,bytes);continue;}if(op!=1)continue;
  JSONObject d=new JSONObject(new String(bytes,StandardCharsets.UTF_8));String type=d.optString("type");if(type.equals("keyframe"))requestKey.run();else if(type.equals("clock")){d.put("phoneUs",System.nanoTime()/1000);send(1,d.toString().getBytes(StandardCharsets.UTF_8));}else messages.accept(d);
 }}
 private int readByte()throws IOException{int b=input.read();if(b<0)throw new EOFException();return b;}
 private void send(int opcode,byte[] data)throws IOException{synchronized(writeLock){if(!online)throw new IOException("Socket reconnecting");byte[] mask=new byte[4];random.nextBytes(mask);output.write(0x80|opcode);if(data.length<126)output.write(0x80|data.length);else if(data.length<=65535){output.write(0x80|126);output.write(data.length>>>8);output.write(data.length);}else{output.write(0x80|127);for(int i=7;i>=0;i--)output.write((int)((long)data.length>>>(i*8)));}output.write(mask);byte[] part=new byte[Math.min(16384,Math.max(1,data.length))];for(int offset=0;offset<data.length;){int n=Math.min(part.length,data.length-offset);for(int i=0;i<n;i++)part[i]=(byte)(data[offset+i]^mask[(offset+i)%4]);output.write(part,0,n);offset+=n;}output.flush();}}
 private void dropConnection(){online=false;Socket s=socket;if(s!=null)try{s.close();}catch(Exception ignored){}packets.clear();waitKey=true;}
 public void close(){if(!closed.compareAndSet(false,true))return;dropConnection();watchdog.shutdownNow();if(manager!=null)manager.interrupt();if(writer!=null)writer.interrupt();}
}
