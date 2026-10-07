import com.mirrorlink.mobile.AudioClock;
public class AudioClockTest {
 public static void main(String[] args){
  AudioClock c=new AudioClock();long anchor=5000000;
  for(int i=0;i<10000;i++){long jitter=(i%7-3)*3000L;long pts=c.timestamp(i*960L,anchor+i*20000L+jitter,anchor+(i+1)*20000L,960);if(pts!=anchor-9000+i*20000L)throw new AssertionError("discontinuous PCM clock");}
  AudioClock fallback=new AudioClock();if(fallback.timestamp(0,0,5020000,960)!=5000000||fallback.timestamp(960,9,5041000,960)!=5020000)throw new AssertionError("fallback clock");
  System.out.println("PASS PCM sample continuity across timestamp jitter and missing timestamps");
 }
}
