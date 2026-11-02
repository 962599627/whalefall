package com.pilipala.marcomovie;

/** A 960 by 540 logical TV canvas, independent of vendor-reported display density. */
final class TvLayoutPolicy {
    static int percent(int value){return value==110||value==120?value:100;}
    static int searchColumns(float availableWidthDp){return Math.max(2,Math.min(4,(int)(availableWidthDp/132)));}
    static int homeColumns(float widthDp,int requested){return Math.max(3,Math.min(Math.max(5,Math.min(8,requested)),(int)((widthDp-120)/140)));}
    static int densityDpi(int width,int height,int percent){
        if(width<=0||height<=0)return 160;
        float scale=Math.min(Math.max(width,height)/960f,Math.min(width,height)/540f)*percent(percent)/100f;
        return Math.max(80,Math.min(1920,Math.round(scale*160)));
    }
}
