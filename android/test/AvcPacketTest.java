import com.mirrorlink.mobile.AvcPacket;
import java.nio.ByteBuffer;
public class AvcPacketTest{
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){byte[] sps={0,0,0,1,0x67,0x42,0,0x28,1,2,3};byte[] pps={0,0,0,1,0x68,1,2};byte[] csd=AvcPacket.parameterSets(ByteBuffer.wrap(sps),ByteBuffer.wrap(pps));check(AvcPacket.codecString(csd).equals("avc1.420028"));byte[] avcc={0,0,0,3,0x65,1,2};byte[] key=AvcPacket.packet(2,7,12345,true,csd,avcc);ByteBuffer b=ByteBuffer.wrap(key);check(b.getInt()==0x594c5631);check(b.get()==1);b.position(8);check(b.getInt()==2&&b.getLong()==12345&&b.getInt()==7);check(key.length==24+csd.length+7);byte[] delta=AvcPacket.packet(2,8,14000,false,csd,avcc);check(delta.length==31&&delta[4]==0);try{AvcPacket.annexB(new byte[]{0,0,0,9,1});throw new AssertionError();}catch(IllegalArgumentException expected){}System.out.println("PASS: AVC parameter sets, codec profile, length-prefix conversion, keyframe headers, malformed data");}
}
