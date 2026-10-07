package com.mirrorlink.mobile;
import android.app.Activity;
import android.net.nsd.*;
import android.os.Handler;
import android.os.Looper;
import java.util.*;
import java.util.function.BiConsumer;

final class ComputerDiscovery implements AutoCloseable {
    private final NsdManager manager;private final Handler main=new Handler(Looper.getMainLooper());private final ArrayDeque<NsdServiceInfo> queue=new ArrayDeque<>();private final BiConsumer<String,String> found;
    private boolean closed,resolving;private final NsdManager.DiscoveryListener listener;
    ComputerDiscovery(Activity activity,BiConsumer<String,String> found){this.found=found;manager=activity.getSystemService(NsdManager.class);listener=new NsdManager.DiscoveryListener(){public void onDiscoveryStarted(String t){}public void onDiscoveryStopped(String t){}public void onStartDiscoveryFailed(String t,int error){close();}public void onStopDiscoveryFailed(String t,int error){}public void onServiceLost(NsdServiceInfo info){}public void onServiceFound(NsdServiceInfo info){main.post(()->{if(!closed&&queue.size()<16){queue.add(info);resolve();}});}};try{manager.discoverServices("_yinlian._tcp.",NsdManager.PROTOCOL_DNS_SD,listener);}catch(Exception e){closed=true;}}
    private void resolve(){if(closed||resolving||queue.isEmpty())return;resolving=true;manager.resolveService(queue.remove(),new NsdManager.ResolveListener(){public void onResolveFailed(NsdServiceInfo info,int error){main.post(()->{resolving=false;resolve();});}public void onServiceResolved(NsdServiceInfo info){main.post(()->{resolving=false;if(closed)return;try{String address=info.getHost().getHostAddress()+":"+info.getPort();Api.baseUrl(address);byte[] value=info.getAttributes().get("name");String name=value==null?info.getServiceName():new String(value,java.nio.charset.StandardCharsets.UTF_8);found.accept(name,address);}catch(Exception ignored){}resolve();});}});}
    public void close(){if(closed)return;closed=true;queue.clear();try{manager.stopServiceDiscovery(listener);}catch(Exception ignored){}}
}
