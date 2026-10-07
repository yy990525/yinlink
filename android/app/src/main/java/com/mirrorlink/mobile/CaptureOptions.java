package com.mirrorlink.mobile;

/** The quality level and the FPS target are independent. */
public final class CaptureOptions {
    public final int fps,longEdge,jpegQuality;
    public CaptureOptions(int fps,int longEdge,int jpegQuality){
        if(fps<1||fps>120)throw new IllegalArgumentException("FPS 需为 1–120 的整数");
        if(longEdge<640||longEdge>3840)throw new IllegalArgumentException("最长边需为 640–3840 像素");
        if(jpegQuality<40||jpegQuality>95)throw new IllegalArgumentException("画质等级需为 40–95 的整数");
        this.fps=fps;this.longEdge=longEdge;this.jpegQuality=jpegQuality;
    }
    public static CaptureOptions safe(int fps,int edge,int quality){return new CaptureOptions(Math.max(1,Math.min(120,fps)),Math.max(640,Math.min(3840,edge)),Math.max(40,Math.min(95,quality)));}
    public static final class Pacer {
        private final long interval;private long next;
        public Pacer(int fps){if(fps<1||fps>120)throw new IllegalArgumentException("FPS");interval=1000000000L/fps;}
        public boolean take(long now){if(now<next)return false;next=Math.max(next+interval,now+interval/2);return true;}
    }
}
