package com.mirrorlink.mobile;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import android.media.projection.MediaProjection;
import android.os.Build;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;

final class AudioStreamer {
    private volatile boolean running;
    private volatile AudioRecord record;
    private final ArrayBlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(3);
    private final String base,token;private final boolean persistent;private volatile VideoSocket socket;
    AudioStreamer(String base,String token,boolean persistent){this.base=base;this.token=token;this.persistent=persistent;}
    void start(Context context,MediaProjection projection){
        if(Build.VERSION.SDK_INT<29){CaptureService.setAudioState(this,"unsupported","声音同步需要 Android 10 及以上");return;}
        if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){CaptureService.setAudioState(this,"denied","请允许录音权限，用于采集应用播放声音");return;}
        try {
            AudioPlaybackCaptureConfiguration config=new AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            AudioFormat format=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            int minimum=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(minimum<=0)throw new IllegalStateException("设备不支持 48kHz 音频");
            record=new AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(Math.max(minimum,7680)).setAudioPlaybackCaptureConfig(config).build();
            if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("音频采集初始化失败");
            record.startRecording();running=true;CaptureService.setAudioState(this,"capturing","声音同步已开启；应用可限制播放音频采集");
            new Thread(this::capture,"MirrorLink-AudioCapture").start();new Thread(this::send,"MirrorLink-AudioSend").start();
        } catch(Exception e){stop();if(record!=null){try{record.release();}catch(Exception ignored){}record=null;}CaptureService.setAudioState(this,"error","声音采集失败："+e.getMessage());}
    }
    private void capture(){AudioRecord source=record;try{
        byte[] data=new byte[1920];int offset=0,silent=0,sequence=0;long framesRead=0;AudioClock clock=new AudioClock();AudioTimestamp stamp=new AudioTimestamp();
        while(running){int count=source.read(data,offset,data.length-offset,AudioRecord.READ_BLOCKING);if(!running)break;if(count<0)throw new IllegalStateException("音频读取失败 "+count);if(count==0)continue;offset+=count;if(offset<data.length)continue;offset=0;
            long firstFrame=framesRead;framesRead+=data.length/2;
            long readEndUs=System.nanoTime()/1000,candidateUs=0;
            if(source.getTimestamp(stamp,AudioTimestamp.TIMEBASE_MONOTONIC)==AudioRecord.SUCCESS)candidateUs=stamp.nanoTime/1000+(firstFrame-stamp.framePosition)*1000000L/48000;
            long pts=clock.timestamp(firstFrame,candidateUs,readEndUs,data.length/2);
            boolean nonzero=false;for(int i=0;i<data.length;i+=2){int sample=(short)((data[i]&255)|(data[i+1]<<8));if(Math.abs(sample)>32){nonzero=true;break;}}
            silent=nonzero?0:silent+1;if(silent==100)CaptureService.setAudioState(this,"silent","暂无可采集音频：请播放媒体；应用可能禁止采集");else if(nonzero)CaptureService.setAudioState(this,"capturing","正在同步应用播放声音");
            byte[] next;if(persistent){java.nio.ByteBuffer packet=java.nio.ByteBuffer.allocate(24+data.length);packet.putInt(0x594c4131).put((byte)1).put((byte)0).putShort((short)48000).putLong(pts).putInt(++sequence).putInt(data.length).put(data);next=packet.array();}else next=Arrays.copyOf(data,data.length);
            if(!queue.offer(next)){queue.poll();queue.offer(next);}
        }
    }catch(Exception e){if(running)CaptureService.setAudioState(this,"error","音频采集已中断");}finally{running=false;if(socket!=null)socket.close();try{source.release();}catch(Exception ignored){}if(record==source)record=null;}}
    private void send(){try{
        if(persistent)socket=new VideoSocket(base,token,"/api/audio-stream",()->{},e->{CaptureService.setAudioState(this,"error","声音连接中断，可重新开启声音");stop();},d->{});
        while(running){byte[] bytes=queue.poll(200,java.util.concurrent.TimeUnit.MILLISECONDS);if(bytes==null||!running)continue;if(persistent)socket.offer(bytes,true,0);else Api.post(base,"/api/audio",token,bytes,"audio/pcm");}
    }catch(Exception e){if(running){CaptureService.setAudioState(this,"error","声音同步已中断，可重新开启声音");stop();}}finally{if(socket!=null)socket.close();}}
    void stop(){running=false;queue.clear();if(socket!=null)socket.close();AudioRecord source=record;if(source!=null){try{source.stop();}catch(Exception ignored){}}}
}
