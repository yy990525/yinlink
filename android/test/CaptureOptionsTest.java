import com.mirrorlink.mobile.CaptureOptions;
public class CaptureOptionsTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void invalid(int fps,int edge,int quality){try{new CaptureOptions(fps,edge,quality);throw new AssertionError("invalid accepted");}catch(IllegalArgumentException expected){}}
 public static void main(String[] args){
  for(int fps:new int[]{1,15,24,30,60,90,120}){
   CaptureOptions o=new CaptureOptions(fps,3840,95);check(o.fps==fps&&o.longEdge==3840&&o.jpegQuality==95,"independent options");
   CaptureOptions.Pacer p=new CaptureOptions.Pacer(fps);int frames=0;for(long t=0;t<1000000000L;t+=1000000L)if(p.take(t))frames++;
   check(frames==fps,"cadence "+fps+" got "+frames);
   check(p.take(5000000000L),"resume after stall");check(!p.take(5000000001L),"no catch-up burst");
  }
  for(int[] a:new int[][]{{0,1280,68},{121,1280,68},{30,639,68},{30,3841,68},{30,1280,39},{30,1280,96}})invalid(a[0],a[1],a[2]);
  CaptureOptions safe=CaptureOptions.safe(999,9999,-1);check(safe.fps==120&&safe.longEdge==3840&&safe.jpegQuality==40,"service bounds");
  System.out.println("PASS: 1/15/24/30/60/90/120 FPS cadence, independent settings, invalid bounds, service clamps, no post-stall bursts");
 }
}
