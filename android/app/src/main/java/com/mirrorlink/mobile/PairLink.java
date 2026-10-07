package com.mirrorlink.mobile;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** QR codes contain a LAN address and an expiring PIN, never an admin/session token. */
public final class PairLink {
    public final String address,code;
    private PairLink(String address,String code){this.address=address;this.code=code;}
    public static PairLink parse(String text){try{
        if(text==null||text.length()>512)throw new IllegalArgumentException();
        URI uri=new URI(text.trim());if(!"yinlian".equals(uri.getScheme())||!"pair".equals(uri.getHost())||uri.getFragment()!=null)throw new IllegalArgumentException();
        Map<String,String> fields=new HashMap<>();for(String p:uri.getRawQuery().split("&")){String[] v=p.split("=",2);if(v.length!=2)throw new IllegalArgumentException();String k=URLDecoder.decode(v[0],StandardCharsets.UTF_8),value=URLDecoder.decode(v[1],StandardCharsets.UTF_8);if(fields.put(k,value)!=null)throw new IllegalArgumentException();}
        String address=Api.baseUrl(fields.getOrDefault("address","")).substring(7),code=fields.getOrDefault("code","");if(!code.matches("[0-9]{6}"))throw new IllegalArgumentException();return new PairLink(address,code);
    }catch(Exception e){throw new IllegalArgumentException("请扫描吟链电脑端的连接二维码");}}
}
