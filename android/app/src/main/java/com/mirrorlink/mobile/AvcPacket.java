package com.mirrorlink.mobile;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;

/** AVC access units in Annex B form; every keyframe includes SPS/PPS. */
public final class AvcPacket {
    private static boolean start(byte[] a,int i){return i+3<a.length&&a[i]==0&&a[i+1]==0&&(a[i+2]==1||a[i+2]==0&&a[i+3]==1);}
    public static byte[] annexB(byte[] a){if(start(a,0))return a;ByteArrayOutputStream out=new ByteArrayOutputStream();int i=0;while(i+4<=a.length){int n=ByteBuffer.wrap(a,i,4).getInt();i+=4;if(n<=0||n>a.length-i)throw new IllegalArgumentException("无效 AVC 单元");out.write(0);out.write(0);out.write(0);out.write(1);out.write(a,i,n);i+=n;}if(i!=a.length||out.size()==0)throw new IllegalArgumentException("无效 AVC 数据");return out.toByteArray();}
    private static byte[] bytes(ByteBuffer b){if(b==null)return new byte[0];ByteBuffer copy=b.duplicate();byte[] a=new byte[copy.remaining()];copy.get(a);return annexB(a);}
    public static byte[] parameterSets(ByteBuffer a,ByteBuffer b){byte[] aa=bytes(a),bb=bytes(b);ByteBuffer out=ByteBuffer.allocate(aa.length+bb.length);out.put(aa).put(bb);return out.array();}
    public static String codecString(byte[] csd){for(int i=0;i+7<csd.length;i++)if(start(csd,i)){int n=csd[i+2]==1?i+3:i+4;if((csd[n]&31)==7)return String.format(Locale.ROOT,"avc1.%02x%02x%02x",csd[n+1]&255,csd[n+2]&255,csd[n+3]&255);}throw new IllegalArgumentException("缺少 H.264 SPS");}
    public static byte[] packet(int epoch,int seq,long pts,boolean key,byte[] csd,byte[] unit){byte[] data=annexB(unit);int extra=key?csd.length:0;ByteBuffer out=ByteBuffer.allocate(24+extra+data.length);out.putInt(0x594c5631).put((byte)(key?1:0)).put(new byte[3]).putInt(epoch).putLong(Math.max(0,pts)).putInt(seq);if(key)out.put(csd);out.put(data);return out.array();}
}
