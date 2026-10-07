package com.mirrorlink.mobile;

/** Hysteresis prevents oscillation. Resolution/FPS stay within the user's chosen ceilings. */
public final class AdaptiveQuality {
    public static final String[] MODES={"flow","balanced","quality"};
    public final int requestedFps,requestedEdge; public String mode;
    private int bad,good;private long lastChange;
    public AdaptiveQuality(int fps,int edge,String mode){requestedFps=fps;requestedEdge=edge;this.mode=validMode(mode);}
    public static String validMode(String value){for(String m:MODES)if(m.equals(value))return m;return "balanced";}
    public int bufferMs(){return mode.equals("flow")?40:mode.equals("quality")?90:60;}
    public int decide(long now,int fps,int edge,double delay,int drops,int queue,boolean hot){
        boolean congested=delay>bufferMs()+120||drops>4||queue>=3||hot;
        if(congested){bad++;good=0;}else{good++;bad=0;}
        if(now-lastChange<6000)return 0;
        if(bad>=3){bad=good=0;lastChange=now;return -1;}
        if(good>=15&&(fps<requestedFps||edge<requestedEdge)){bad=good=0;lastChange=now;return 1;}
        return 0;
    }
    public int lowerFps(int fps){return Math.max(Math.min(requestedFps,24),Math.min(requestedFps,(int)(fps*.8)));}
    public int lowerEdge(int edge){return Math.max(Math.min(requestedEdge,960),(int)(edge*.8)/2*2);}
}
