package com.mirrorlink.mobile;

/** A sample-count clock prevents per-read timestamp jitter from splitting PCM blocks. */
public final class AudioClock {
    private long anchorUs=Long.MIN_VALUE;
    public long timestamp(long firstFrame,long candidateUs,long readEndUs,int count){
        long fallback=readEndUs-count*1000000L/48000;
        if(anchorUs==Long.MIN_VALUE){
            long firstUs=candidateUs>0&&Math.abs(candidateUs-fallback)<250000?candidateUs:fallback;
            anchorUs=firstUs-firstFrame*1000000L/48000;
        }
        return anchorUs+firstFrame*1000000L/48000;
    }
}
