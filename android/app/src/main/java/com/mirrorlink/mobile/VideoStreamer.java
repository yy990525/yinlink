package com.mirrorlink.mobile;

import android.media.*;
import android.os.*;
import android.view.Surface;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Surface -> AVC hardware encoder -> persistent socket, with no RGBA/Bitmap/JPEG copies. */
final class VideoStreamer implements AutoCloseable {
    final Handler handler;private final java.util.function.Consumer<Exception> failure;private VideoSocket socket;private MediaCodec codec;private Surface surface;
    private final AtomicBoolean wantKey=new AtomicBoolean(true);private volatile boolean closed;private byte[] csd=new byte[0];private int epoch,sequence,quality,targetFps,maxBitrate;private long lastRequest,lastDrop,lastRecovery,ptsOffset=Long.MIN_VALUE;private final MediaCodec.BufferInfo bufferInfo=new MediaCodec.BufferInfo();
    volatile int width,height,effectiveFps,bitrate;volatile String encoderName="";
    VideoStreamer(Handler handler,String base,String token,int quality,java.util.function.Consumer<Exception> failure,java.util.function.Consumer<JSONObject> messages)throws Exception{this.handler=handler;this.quality=quality;this.failure=failure;socket=new VideoSocket(base,token,"/api/video",()->wantKey.set(true),failure,d->handler.post(()->{if(closed)return;if(d.optString("type").equals("feedback")){if(d.optDouble("delay")>200||d.optInt("drops")>4||d.optInt("queue")>=3){lastDrop=SystemClock.elapsedRealtime();changeBitrate(Math.max(1500000,(int)(bitrate*.85)));}}messages.accept(d);}));}
    Surface configure(int desiredWidth,int desiredHeight,int fps)throws Exception{
        stopCodec();targetFps=fps;List<MediaCodecInfo> encoders=new ArrayList<>();for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos())if(info.isEncoder()&&Arrays.asList(info.getSupportedTypes()).contains("video/avc")&&(Build.VERSION.SDK_INT>=29?info.isHardwareAccelerated():!info.getName().startsWith("OMX.google.")&&!info.getName().startsWith("c2.android.")))encoders.add(info);
        Exception last=null;
        for(double scale:new double[]{1,.85,.7,.5})for(MediaCodecInfo info:encoders){MediaCodec candidate=null;Surface next=null;try{
            MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType("video/avc");MediaCodecInfo.VideoCapabilities video=caps.getVideoCapabilities();int w=Math.max(video.getWidthAlignment(),(int)(desiredWidth*scale)/video.getWidthAlignment()*video.getWidthAlignment()),h=Math.max(video.getHeightAlignment(),(int)(desiredHeight*scale)/video.getHeightAlignment()*video.getHeightAlignment());if(!video.isSizeSupported(w,h))continue;
            int actual=Math.max(1,Math.min(fps,(int)Math.floor(video.getSupportedFrameRatesFor(w,h).getUpper())));
            int budget=(int)Math.min(60000000,Math.max(2000000,(long)w*h*actual*(0.035+quality*0.001)));
            budget=video.getBitrateRange().clamp(budget);
            MediaFormat format=MediaFormat.createVideoFormat("video/avc",w,h);format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);format.setInteger(MediaFormat.KEY_BIT_RATE,budget);format.setInteger(MediaFormat.KEY_FRAME_RATE,actual);format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);format.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);format.setInteger(MediaFormat.KEY_PRIORITY,0);format.setInteger(MediaFormat.KEY_LATENCY,0);format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER,actual);if(Build.VERSION.SDK_INT>=29)format.setInteger(MediaFormat.KEY_MAX_B_FRAMES,0);
            MediaCodecInfo.EncoderCapabilities encoderCaps=caps.getEncoderCapabilities();if(encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR))format.setInteger(MediaFormat.KEY_BITRATE_MODE,MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            candidate=MediaCodec.createByCodecName(info.getName());try{candidate.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);}catch(Exception tuningFailure){candidate.release();candidate=MediaCodec.createByCodecName(info.getName());MediaFormat basic=MediaFormat.createVideoFormat("video/avc",w,h);basic.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);basic.setInteger(MediaFormat.KEY_BIT_RATE,budget);basic.setInteger(MediaFormat.KEY_FRAME_RATE,actual);basic.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);basic.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);candidate.configure(basic,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);}next=candidate.createInputSurface();candidate.start();codec=candidate;surface=next;width=w;height=h;effectiveFps=actual;bitrate=maxBitrate=budget;encoderName=info.getName();epoch++;sequence=0;ptsOffset=Long.MIN_VALUE;csd=new byte[0];wantKey.set(true);lastRecovery=SystemClock.elapsedRealtime();handler.removeCallbacks(drain);handler.post(drain);return surface;
        }catch(Exception e){last=e;if(next!=null)next.release();if(candidate!=null){try{candidate.release();}catch(Exception ignored){}}}}
        throw new IllegalStateException("手机没有可用的 H.264 硬件编码器"+(last!=null?"："+last.getMessage():""));
    }
    private final Runnable drain=new Runnable(){public void run(){if(closed||codec==null)return;try{
        long now=SystemClock.elapsedRealtime();if(wantKey.get()&&now-lastRequest>350){wantKey.set(false);lastRequest=now;Bundle params=new Bundle();params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0);codec.setParameters(params);}
        if(now-lastDrop>5000&&now-lastRecovery>5000&&bitrate<maxBitrate){lastRecovery=now;changeBitrate(Math.min(maxBitrate,(int)(bitrate*1.1)));}
        MediaCodec.BufferInfo info=bufferInfo;for(int n=0;n<12;n++){int index=codec.dequeueOutputBuffer(info,0);if(index==MediaCodec.INFO_TRY_AGAIN_LATER)break;if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){publishConfig(codec.getOutputFormat());continue;}if(index<0)continue;
            try{ByteBuffer b=codec.getOutputBuffer(index);if(b==null||info.size<=0)continue;b.position(info.offset);b.limit(info.offset+info.size);byte[] raw=new byte[info.size];b.get(raw);if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0){csd=AvcPacket.annexB(raw);continue;}boolean key=(info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0;if(ptsOffset==Long.MIN_VALUE){long clock=System.nanoTime()/1000;ptsOffset=Math.abs(clock-info.presentationTimeUs)>10000000?clock-info.presentationTimeUs:0;}byte[] packet=AvcPacket.packet(epoch,++sequence,info.presentationTimeUs+ptsOffset,key,csd,raw);if(!socket.offer(packet,key,epoch)){wantKey.set(true);if(now-lastDrop>1000){lastDrop=now;changeBitrate(Math.max(2000000,(int)(bitrate*.85)));}}}
            finally{codec.releaseOutputBuffer(index,false);}
        }
    }catch(Exception e){if(!closed)failure.accept(e);return;}if(!closed)handler.postDelayed(this,2);}};
    void setQuality(int value){quality=value;}
    boolean connected(){return socket.connected();}
    private void changeBitrate(int next){try{Bundle params=new Bundle();params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE,next);codec.setParameters(params);bitrate=next;}catch(Exception ignored){}}
    private void publishConfig(MediaFormat format)throws Exception{ByteBuffer a=format.getByteBuffer("csd-0"),b=format.getByteBuffer("csd-1");csd=AvcPacket.parameterSets(a,b);String codecString=AvcPacket.codecString(csd);socket.config(new JSONObject().put("type","config").put("codec",codecString).put("width",width).put("height",height).put("epoch",epoch).put("fps",effectiveFps).put("bitrate",bitrate).put("encoder",encoderName).toString(),epoch);}
    private void stopCodec(){handler.removeCallbacks(drain);if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();codec=null;}if(surface!=null){surface.release();surface=null;}}
    public void close(){closed=true;handler.removeCallbacks(drain);if(socket!=null)socket.close();stopCodec();}
}
