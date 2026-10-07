package com.mirrorlink.mobile;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class Api {
    static final class Failure extends IOException {
        final int status;
        Failure(int status, String message) { super(message); this.status = status; }
    }
    static JSONObject post(String base, String route, String token, byte[] data, String type) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + route).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(4000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", type);
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setFixedLengthStreamingMode(data.length);
        try {
            try (java.io.OutputStream stream = connection.getOutputStream()) { stream.write(data); }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = "";
            if (input != null) {
                try (InputStream stream = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[1024]; int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (out.size() + count > 8192) throw new IOException("电脑响应过大");
                        out.write(buffer, 0, count);
                    }
                    text = out.toString("UTF-8");
                }
            }
            JSONObject body;
            try { body = new JSONObject(text); } catch (Exception e) { throw new IOException("电脑返回了无效响应"); }
            if (status < 200 || status >= 300) throw new Failure(status, body.optString("error", "连接失败（" + status + "）"));
            return body;
        } finally { connection.disconnect(); }
    }
    static JSONObject json(String base, String route, String token, JSONObject body) throws Exception {
        return post(base, route, token, body.toString().getBytes(StandardCharsets.UTF_8), "application/json");
    }
    static JSONObject get(String base,String route,String token) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(base+route).openConnection();
        connection.setConnectTimeout(3000);connection.setReadTimeout(4000);connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization","Bearer "+token);
        try { int status=connection.getResponseCode();InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();
            if(input==null)throw new IOException("电脑无响应");
            try(InputStream stream=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] bytes=new byte[2048];int count;while((count=stream.read(bytes))!=-1){if(out.size()+count>65536)throw new IOException("输入响应过大");out.write(bytes,0,count);}JSONObject result=new JSONObject(out.toString("UTF-8"));if(status<200||status>=300)throw new Failure(status,result.optString("error","连接中断"));return result;}
        }finally{connection.disconnect();}
    }
    static String baseUrl(String input) {
        String value = input.trim();
        if (value.startsWith("http://")) value = value.substring(7);
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        String[] parts = value.split(":", -1);
        if (parts.length > 2) throw new IllegalArgumentException("请输入电脑的 IPv4 地址，例如 192.168.1.8:52345");
        String[] octets = parts[0].split("\\.", -1);
        if (octets.length != 4) throw new IllegalArgumentException("请输入电脑端显示的局域网 IPv4 地址");
        int[] numbers = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (!octets[i].matches("[0-9]{1,3}")) throw new NumberFormatException();
                numbers[i] = Integer.parseInt(octets[i]);
                if (numbers[i] > 255) throw new NumberFormatException();
            }
            int port = parts.length == 2 ? Integer.parseInt(parts[1]) : 52345;
            if (port < 1 || port > 65535) throw new NumberFormatException();
            boolean local = numbers[0] == 10 || numbers[0] == 192 && numbers[1] == 168 || numbers[0] == 172 && numbers[1] >= 16 && numbers[1] <= 31 || numbers[0] == 169 && numbers[1] == 254;
            if (!local) throw new IllegalArgumentException("请使用可信局域网中的电脑地址（10.x、172.16–31.x 或 192.168.x）");
            return "http://" + numbers[0] + "." + numbers[1] + "." + numbers[2] + "." + numbers[3] + ":" + port;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("电脑地址或端口格式不正确"); }
    }
}
