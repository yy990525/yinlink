import com.mirrorlink.mobile.ConnectionHistory;
public class ConnectionHistoryTest {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  ConnectionHistory h=new ConnectionHistory();h.record("192.168.1.8:52345","012345",100);h.record("10.0.0.8:52345","",200);
  ConnectionHistory loaded=ConnectionHistory.decode(h.encode());check(loaded.entries().size()==2,"persistence");check(loaded.entries().get(0).fixedCode.isEmpty(),"random PIN not saved");check(loaded.entries().get(1).fixedCode.equals("012345"),"fixed leading zero");
  h.record("192.168.1.8:52345","",300);check(h.entries().size()==2&&h.entries().get(0).fixedCode.isEmpty(),"deduplicate, mode changes drop stale fixed PIN");
  for(int n=1;n<=15;n++)h.record("192.168.0."+n+":52345","",n);check(h.entries().size()==10&&h.entries().get(0).address.contains(".15:"),"limit and recency");
  h.remove("192.168.0.15:52345");check(h.entries().size()==9,"delete");h.clear();check(h.entries().isEmpty(),"clear");
  check(ConnectionHistory.decode("corrupt").entries().isEmpty(),"corruption recovery");check(ConnectionHistory.decode("x".repeat(16001)).entries().isEmpty(),"size bound");
  try{h.record("192.168.1.8:52345","abcdef",0);throw new AssertionError("bad PIN accepted");}catch(IllegalArgumentException ok){}
  System.out.println("PASS: history persistence, fixed/random PIN handling, recency, deduplication, 10-entry cap, delete, clear, corrupt storage recovery");
 }
}
