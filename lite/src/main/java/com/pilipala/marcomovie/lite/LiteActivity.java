// Copyright (C) 2026 Pilipala. SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.marcomovie.lite;

import android.app.Activity;
import android.os.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;

/** First-stage API14 shell: configuration -> CMS -> detail -> system playback. */
public final class LiteActivity extends Activity {
 final Handler ui=new Handler();
 final ThreadPoolExecutor io=new ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<Runnable>(8),new ThreadPoolExecutor.DiscardOldestPolicy());
 Future<?> activeWork;
 LinearLayout page;TextView status;ConfigServer pairing;LitePlayer player;SurfaceView surface;FrameLayout playback;
 java.util.List<LiteCatalog.Site> sites=new ArrayList<LiteCatalog.Site>();LiteCatalog.Site selected;
 volatile int epoch;volatile boolean ready,dead;JSONObject currentDetail;final Object importLock=new Object();boolean homeVisible,inPlayback,backgrounded;
 interface Job {void run()throws Exception;}
 @Override public void onCreate(Bundle state){super.onCreate(state);home();}
 int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
 TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(0xffeeeeF2);t.setPadding(dp(8),dp(8),dp(8),dp(8));return t;}
 void cancelWork(){if(activeWork!=null){activeWork.cancel(true);activeWork=null;}io.purge();LiteCatalog.cancelAll();}
 void base(String title){homeVisible=false;inPlayback=false;epoch++;cancelWork();closePairing();release();ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xff101013);page=new LinearLayout(this);page.setOrientation(1);page.setPadding(dp(32),dp(16),dp(32),dp(24));scroll.addView(page);setContentView(scroll);page.addView(text(title,28));status=text("",16);page.addView(status);}
 void button(String title,final Runnable action){final TextView b=text(title,20);b.setGravity(Gravity.CENTER);b.setFocusable(true);b.setClickable(true);style(b,false);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));lp.setMargins(dp(4),dp(4),dp(4),dp(4));page.addView(b,lp);b.setOnFocusChangeListener(new View.OnFocusChangeListener(){public void onFocusChange(View v,boolean focus){style(b,focus);}});b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){action.run();}});}
 void style(TextView b,boolean focused){GradientDrawable bg=new GradientDrawable();bg.setColor(focused?0xffeeeeF2:0xff28282f);bg.setCornerRadius(dp(16));b.setBackgroundDrawable(bg);b.setTextColor(focused?0xff101013:0xffeeeeF2);}
 void work(final Job job){final int id=epoch;status.setText("正在加载…");cancelWork();activeWork=io.submit(new Runnable(){public void run(){if(dead||epoch!=id)return;try{job.run();}catch(final Exception ex){ui.post(new Runnable(){public void run(){if(!dead&&epoch==id)status.setText("加载失败，请重试。请检查网络、配置类型及旧系统 HTTPS 兼容性。");}});}}});}
 void home(){base("MARCO MOVIE  Lite");homeVisible=true;if(PlayerFactory.hasCandidate())button(getPreferences(0).getBoolean("experimental_decoder",false)?"解码：IJK 实验版":"解码：系统",new Runnable(){public void run(){getPreferences(0).edit().putBoolean("experimental_decoder",!getPreferences(0).getBoolean("experimental_decoder",false)).commit();home();}});status.setText("Android 4.0 兼容验证版 · 不含任何影视资源");button("手机扫码配置",new Runnable(){public void run(){configure();}});String raw=getPreferences(0).getString("config","");try{if(raw.length()>0)sites=LiteCatalog.parse(raw,getPreferences(0).getString("address",""));}catch(Exception ignored){}for(final LiteCatalog.Site s:sites)button(s.name+(s.supported?"":"  · 尚未兼容"),new Runnable(){public void run(){if(s.supported){selected=s;catalog("",1);}else status.setText("此来源需要插件、JS 或网页解析，兼容验证版尚未支持。");}});}
 void configure(){base("手机配置");final int session=epoch;status.setText("手机与电视连接同一局域网；配置只保存在本机。");try{
  pairing=new ConfigServer(new ConfigServer.Importer(){public void apply(String address)throws Exception {String raw=LiteCatalog.fetch(address,null);LiteCatalog.parse(raw,address);synchronized(importLock){if(dead||pairing==null||epoch!=session)throw new java.io.IOException();if(!getPreferences(0).edit().putString("address",address).putString("config",raw).commit())throw new java.io.IOException();}ui.post(new Runnable(){public void run(){if(pairing!=null)status.setText("配置已保存，点击“返回片库”选择片源。");}});}});
  String host=null;Enumeration<NetworkInterface> nets=NetworkInterface.getNetworkInterfaces();while(nets.hasMoreElements()){Enumeration<InetAddress> addresses=nets.nextElement().getInetAddresses();while(addresses.hasMoreElements()){InetAddress a=addresses.nextElement();if(a instanceof Inet4Address&&!a.isLoopbackAddress()&&a.isSiteLocalAddress())host=a.getHostAddress();}}
  if(host==null)throw new java.io.IOException();String url="http://"+host+":"+pairing.port()+"/"+pairing.token;
  BitMatrix matrix=new MultiFormatWriter().encode(url,BarcodeFormat.QR_CODE,320,320);Bitmap bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.RGB_565);for(int y=0;y<320;y++)for(int x=0;x<320;x++)bitmap.setPixel(x,y,matrix.get(x,y)?0xff000000:0xffffffff);ImageView qr=new ImageView(this);qr.setImageBitmap(bitmap);page.addView(qr,new LinearLayout.LayoutParams(dp(230),dp(230)));page.addView(text(url,14));
 }catch(Exception failure){closePairing();status.setText("请先连接局域网，再打开配置页。");}button("返回片库",new Runnable(){public void run(){home();}});}
 void catalog(final String keyword,final int number){base(selected.name);final int id=epoch;final LiteCatalog.Site site=selected;button("搜索此来源",new Runnable(){public void run(){search();}});work(new Job(){public void run()throws Exception{final JSONObject response=new JSONObject(LiteCatalog.fetch(LiteCatalog.query(site.api,"ac","detail","pg",""+number,"wd",keyword),site.headers));final JSONArray list=response.optJSONArray("list");if(list==null)throw new java.io.IOException();ui.post(new Runnable(){public void run(){if(dead||id!=epoch)return;status.setText(list.length()==0?"没有结果":"第 "+number+" 页");for(int i=0;i<Math.min(list.length(),60);i++){final JSONObject video=list.optJSONObject(i);if(video==null)continue;button(video.optString("vod_name"),new Runnable(){public void run(){detail(video.optString("vod_id"));}});}if(number<response.optInt("pagecount",1))button("下一页",new Runnable(){public void run(){catalog(keyword,number+1);}});}});}});}
 void search(){base("搜索 · "+selected.name);final EditText input=new EditText(this);input.setSingleLine();input.setTextColor(0xffffffff);page.addView(input);button("搜索",new Runnable(){public void run(){catalog(input.getText().toString().trim(),1);}});}
 void detail(final String videoId){base("影片详情");final int id=epoch;final LiteCatalog.Site site=selected;work(new Job(){public void run()throws Exception{final JSONObject video=LiteCatalog.list(LiteCatalog.fetch(LiteCatalog.query(site.api,"ac","detail","ids",videoId),site.headers)).getJSONObject(0);ui.post(new Runnable(){public void run(){if(!dead&&epoch==id)showDetail(video);}});}});}
 void showDetail(final JSONObject video){base(video.optString("vod_name"));currentDetail=video;status.setText(android.text.Html.fromHtml(video.optString("vod_content")).toString());String[] lines=video.optString("vod_play_url").split("\\$\\$\\$");String[] names=video.optString("vod_play_from").split("\\$\\$\\$");for(int i=0;i<lines.length;i++){page.addView(text(i<names.length?names[i]:"线路",18));int count=0;for(String entry:lines[i].split("#")){if(++count>150){page.addView(text("验证版每条线路暂展示前 150 集",16));break;}int sep=entry.indexOf('$');if(sep<0)continue;final String name=entry.substring(0,sep),url=entry.substring(sep+1);button(name,new Runnable(){public void run(){play(url,name);}});}}}
 void play(final String address,String name){
  base(name);inPlayback=true;final int id=epoch;
  playback=new FrameLayout(this);playback.setBackgroundColor(0xff000000);setContentView(playback);
  surface=new SurfaceView(this);playback.addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
  status=text("正在准备播放…",18);status.setBackgroundColor(0xb0101013);
  playback.addView(status,new FrameLayout.LayoutParams(-1,dp(64),Gravity.BOTTOM));
  TextView heading=text(name,24);heading.setBackgroundColor(0x90101013);playback.addView(heading,new FrameLayout.LayoutParams(-1,dp(60),Gravity.TOP));
  surface.getHolder().addCallback(new SurfaceHolder.Callback(){
   public void surfaceCreated(SurfaceHolder holder){if(id!=epoch||dead)return;try{
    LiteCatalog.http(address);player=PlayerFactory.create(getPreferences(0).getBoolean("experimental_decoder",false));final LitePlayer expected=player;
    player.open(LiteActivity.this,address,selected==null?Collections.<String,String>emptyMap():selected.headers,holder,new LitePlayer.Listener(){
     public void prepared(){if(expected!=player)return;ready=true;expected.start();status.setText(expected.name()+" · 确认键暂停／播放 · 左右 15 秒 · 返回选集");}
     public void error(int what,int extra){if(expected!=player)return;ready=false;status.setText(expected.name()+"无法播放（"+what+"/"+extra+"），可返回选择其他资源。");}
     public void size(final int w,final int h){ui.post(new Runnable(){public void run(){if(expected!=player||w<=0||h<=0)return;int width=playback.getWidth(),height=playback.getHeight();if(width<=0||height<=0)return;double scale=Math.min((double)width/w,(double)height/h);surface.setLayoutParams(new FrameLayout.LayoutParams((int)(w*scale),(int)(h*scale),Gravity.CENTER));}});}
     public void completed(){if(expected==player)status.setText("播放结束 · 返回选集");}
    });
    ui.postDelayed(new Runnable(){public void run(){if(player==expected&&!ready)status.setText("播放准备较慢，可按返回选择其他资源。");}},15000);
   }catch(Exception failure){release();status.setText("无法打开播放地址，请返回选择其他资源。");}catch(LinkageError unavailable){release();status.setText("此设备无法加载实验解码器，请使用系统解码。");}}
   public void surfaceChanged(SurfaceHolder h,int f,int w,int height){}
   public void surfaceDestroyed(SurfaceHolder h){if(id==epoch)release();}
  });
 }
 @Override public boolean dispatchKeyEvent(KeyEvent e){if(player!=null&&ready){int k=e.getKeyCode();if(k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER||k==KeyEvent.KEYCODE_DPAD_LEFT||k==KeyEvent.KEYCODE_DPAD_RIGHT){if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0){try{if(k==KeyEvent.KEYCODE_DPAD_LEFT||k==KeyEvent.KEYCODE_DPAD_RIGHT)player.seekTo(Math.max(0,Math.min(player.getDuration(),player.getCurrentPosition()+(k==KeyEvent.KEYCODE_DPAD_LEFT?-15000:15000))));else if(player.isPlaying())player.pause();else player.start();}catch(IllegalStateException ignored){}}return true;}}return super.dispatchKeyEvent(e);}
 void release(){ready=false;if(player!=null){player.release();player=null;}}
 void closePairing(){synchronized(importLock){if(pairing!=null){pairing.close();pairing=null;}}}
 @Override public void onBackPressed(){if(homeVisible){finish();return;}if(inPlayback&&currentDetail!=null){showDetail(currentDetail);return;}home();}
 @Override protected void onStart(){super.onStart();if(backgrounded){backgrounded=false;if(inPlayback&&currentDetail!=null)showDetail(currentDetail);else home();}}
 @Override protected void onStop(){super.onStop();backgrounded=true;epoch++;cancelWork();closePairing();release();}
 @Override protected void onDestroy(){dead=true;epoch++;cancelWork();closePairing();release();io.shutdownNow();super.onDestroy();}
}
