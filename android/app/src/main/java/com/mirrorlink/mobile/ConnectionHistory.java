package com.mirrorlink.mobile;
import java.io.*;
import java.util.*;

/** Local-only recent computers. Session tokens and random PINs are never persisted. */
public final class ConnectionHistory {
    public static final int LIMIT=10;
    public static final class Entry {
        public final String address,fixedCode,name;public final boolean favorite;public final long lastUsed;
        private Entry(String address,String code,long time){this(address,code,time,"",false);}
        private Entry(String address,String code,long time,String name,boolean favorite){this.address=address;fixedCode=code;lastUsed=time;this.name=name;this.favorite=favorite;}
    }
    private final List<Entry> values=new ArrayList<>();
    public List<Entry> entries(){return Collections.unmodifiableList(new ArrayList<>(values));}
    private static Entry valid(String address,String pin,long time){
        if(address==null||address.length()>96||!address.matches("[0-9.]+:[0-9]{1,5}"))throw new IllegalArgumentException("address");
        String[] parts=address.split(":");String[] octets=parts[0].split("\\.",-1);if(octets.length!=4)throw new IllegalArgumentException("address");
        for(String o:octets){int n=Integer.parseInt(o);if(n<0||n>255)throw new IllegalArgumentException("address");}int port=Integer.parseInt(parts[1]);if(port<1||port>65535)throw new IllegalArgumentException("port");
        if(pin==null||!(pin.isEmpty()||pin.matches("[0-9]{6}"))||time<0)throw new IllegalArgumentException("entry");return new Entry(address,pin,time);
    }
    public void record(String address,String fixedCode,long time){Entry entry=valid(address,fixedCode,time);for(Entry old:values)if(old.address.equals(address)){entry=new Entry(entry.address,entry.fixedCode,entry.lastUsed,old.name,old.favorite);break;}remove(address);values.add(0,entry);if(values.size()>LIMIT)values.remove(values.size()-1);}
    public void rename(String address,String name){if(name==null||name.length()>32||name.chars().anyMatch(c->c<32))throw new IllegalArgumentException("name");for(int n=0;n<values.size();n++){Entry e=values.get(n);if(e.address.equals(address))values.set(n,new Entry(e.address,e.fixedCode,e.lastUsed,name.trim(),e.favorite));}}
    public void favorite(String address,boolean selected){for(int n=0;n<values.size();n++){Entry e=values.get(n);if(e.address.equals(address))values.set(n,new Entry(e.address,e.fixedCode,e.lastUsed,e.name,selected));}}
    public void remove(String address){values.removeIf(e->e.address.equals(address));}
    public void clear(){values.clear();}
    public String encode(){try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(2);out.writeInt(values.size());for(Entry e:values){out.writeUTF(e.address);out.writeUTF(e.fixedCode);out.writeLong(e.lastUsed);out.writeUTF(e.name);out.writeBoolean(e.favorite);}out.close();return Base64.getEncoder().encodeToString(bytes.toByteArray());}catch(IOException e){throw new IllegalStateException(e);}}
    public static ConnectionHistory decode(String value){ConnectionHistory result=new ConnectionHistory();if(value==null||value.isEmpty()||value.length()>16000)return result;try{DataInputStream in=new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value)));int version=in.readInt();if(version!=1&&version!=2)throw new IOException("version");int count=in.readInt();if(count<0||count>LIMIT)throw new IOException("count");Set<String> seen=new HashSet<>();for(int n=0;n<count;n++){Entry entry=valid(in.readUTF(),in.readUTF(),in.readLong());if(version==2){String name=in.readUTF();boolean favorite=in.readBoolean();if(name.length()>32||name.chars().anyMatch(c->c<32))throw new IOException("name");entry=new Entry(entry.address,entry.fixedCode,entry.lastUsed,name,favorite);}if(!seen.add(entry.address))throw new IOException("duplicate");result.values.add(entry);}if(in.available()!=0)throw new IOException("trailing");return result;}catch(Exception e){return new ConnectionHistory();}}
}
