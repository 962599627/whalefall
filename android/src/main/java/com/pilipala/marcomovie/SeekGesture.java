package com.pilipala.marcomovie;

/** Remote-key timeline preview. Commit once on release, independent of key repeat rate. */
final class SeekGesture {
    static final int TAP_MS=15000,HOLD_DELAY_MS=500,ACCELERATE_MS=3000;
    boolean active;
    int direction,target,duration;
    long started,updated;
    void begin(int direction,int position,int duration,long now){
        this.direction=direction<0?-1:1;this.duration=Math.max(0,duration);
        target=bound((long)position+this.direction*TAP_MS);started=updated=now;active=duration>0;
    }
    void advance(long now){
        if(!active||now<=updated)return;
        long from=Math.max(updated,started+HOLD_DELAY_MS),to=Math.max(from,now),boundary=started+ACCELERATE_MS;
        long normal=Math.max(0,Math.min(to,boundary)-Math.min(from,boundary));
        long fast=Math.max(0,to-Math.max(from,boundary));
        target=bound((long)target+direction*(normal*30+fast*60));updated=now;
    }
    boolean accelerated(long now){return active&&now-started>=ACCELERATE_MS;}
    int bound(long value){return (int)Math.max(0,Math.min(duration,value));}
    void cancel(){active=false;}
}
