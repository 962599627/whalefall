package com.pilipala.marcomovie;

/** A 960 by 540 logical TV canvas, independent of vendor-reported display density. */
final class TvLayoutPolicy {
    static int percent(int value){return value==110||value==120?value:100;}
    static int densityDpi(int width,int height,int percent){
        if(width<=0||height<=0)return 160;
        float scale=Math.min(Math.max(width,height)/960f,Math.min(width,height)/540f)*percent(percent)/100f;
        return Math.max(80,Math.min(1920,Math.round(scale*160)));
    }
}
