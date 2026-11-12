package com.whalefall.movie;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.nio.ByteBuffer;

/**
 * 1.9.230：4K/超大壁纸视频后台转码为 1080p H.264（进度可取消）。
 * 解码 → 缩放到目标尺寸 → H.264 编码 → MediaMuxer 输出。
 */
final class WallpaperTranscoder {
  private WallpaperTranscoder() {}

  interface Callback {
    void progress(int pct);

    void done(File out);

    void failed(String reason);
  }

  private static volatile boolean running;
  private static volatile boolean cancelRequested;

  static boolean running() {
    return running;
  }

  static void cancel() {
    cancelRequested = true;
    AppLog.i("Wallpaper", "transcode cancel requested");
  }

  static void start(final File src, final File dst, final Callback callback) {
    if (running) {
      callback.failed("busy");
      return;
    }
    running = true;
    cancelRequested = false;
    new Thread(
            () -> {
              try {
                transcode(src, dst, callback);
                callback.done(dst);
              } catch (Throwable error) {
                AppLog.i(
                    "Wallpaper",
                    "transcode failed reason="
                        + error.getClass().getSimpleName()
                        + " msg="
                        + error.getMessage());
                if (dst.exists()) dst.delete();
                callback.failed(error.getClass().getSimpleName());
              } finally {
                running = false;
              }
            },
            "wallpaper-transcode")
        .start();
  }

  private static void transcode(File src, File dst, Callback cb) throws Exception {
    MediaExtractor ex = new MediaExtractor();
    ex.setDataSource(src.getAbsolutePath());
    int track = -1;
    MediaFormat inFormat = null;
    for (int i = 0; i < ex.getTrackCount(); i++) {
      MediaFormat f = ex.getTrackFormat(i);
      String mime = f.getString(MediaFormat.KEY_MIME);
      if (mime != null && mime.startsWith("video/")) {
        track = i;
        inFormat = f;
        break;
      }
    }
    if (track < 0 || inFormat == null) throw new IllegalStateException("no video track");
    ex.selectTrack(track);
    int srcW = inFormat.getInteger(MediaFormat.KEY_WIDTH);
    int srcH = inFormat.getInteger(MediaFormat.KEY_HEIGHT);
    long durationUs = inFormat.containsKey(MediaFormat.KEY_DURATION)
        ? inFormat.getLong(MediaFormat.KEY_DURATION) : 0;
    int outW = srcW, outH = srcH;
    if (Math.max(outW, outH) > 1920) {
      float s = 1920f / Math.max(outW, outH);
      outW = even(Math.round(outW * s));
      outH = even(Math.round(outH * s));
    }
    if (Math.min(outW, outH) > 1080) {
      float s = 1080f / Math.min(outW, outH);
      outW = even(Math.round(outW * s));
      outH = even(Math.round(outH * s));
    }
    AppLog.i(
        "Wallpaper",
        "transcode start src="
            + srcW
            + "x"
            + srcH
            + " dst="
            + outW
            + "x"
            + outH
            + " pct=0");
    String mime = inFormat.getString(MediaFormat.KEY_MIME);
    MediaCodec dec = MediaCodec.createDecoderByType(mime);
    dec.configure(inFormat, null, null, 0);
    dec.start();
    MediaFormat encFormat =
        MediaFormat.createVideoFormat("video/avc", outW, outH);
    encFormat.setInteger(
        MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
    encFormat.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000);
    encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
    encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
    MediaCodec enc = MediaCodec.createEncoderByType("video/avc");
    enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    enc.start();
    MediaMuxer mux = new MediaMuxer(dst.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    int muxTrack = -1;
    boolean muxStarted = false;
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    boolean inDone = false, decDone = false, encDone = false;
    while (!encDone && !cancelRequested) {
      if (!inDone) {
        int in = dec.dequeueInputBuffer(10000);
        if (in >= 0) {
          int size = ex.readSampleData(dec.getInputBuffer(in), 0);
          if (size < 0) {
            dec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            inDone = true;
          } else {
            dec.queueInputBuffer(in, 0, size, ex.getSampleTime(), 0);
            ex.advance();
          }
        }
      }
      if (!decDone) {
        int out = dec.dequeueOutputBuffer(info, 10000);
        if (out >= 0) {
          if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            decDone = true;
            enc.signalEndOfInputStream();
          } else {
            ByteBuffer frame = dec.getOutputBuffer(out);
            if (frame != null) {
              int inIdx = enc.dequeueInputBuffer(10000);
              if (inIdx >= 0) {
                ByteBuffer encIn = enc.getInputBuffer(inIdx);
                scaleYuv(frame, outW, outH, encIn, srcW, srcH);
                enc.queueInputBuffer(inIdx, 0, outW * outH * 3 / 2, info.presentationTimeUs, 0);
              }
            }
          }
          dec.releaseOutputBuffer(out, false);
          if (durationUs > 0)
            cb.progress((int) Math.min(99, info.presentationTimeUs * 100 / durationUs));
        }
      }
      int out = enc.dequeueOutputBuffer(info, 10000);
      if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        muxTrack = mux.addTrack(enc.getOutputFormat());
        mux.start();
        muxStarted = true;
      } else if (out >= 0) {
        ByteBuffer data = enc.getOutputBuffer(out);
        if (data != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
          data.position(info.offset);
          data.limit(info.offset + info.size);
          mux.writeSampleData(muxTrack, data, info);
        }
        enc.releaseOutputBuffer(out, false);
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encDone = true;
      }
    }
    try { dec.stop(); } catch (Throwable ignored) {}
    try { dec.release(); } catch (Throwable ignored) {}
    try { enc.stop(); } catch (Throwable ignored) {}
    try { enc.release(); } catch (Throwable ignored) {}
    try { if (muxStarted) mux.stop(); } catch (Throwable ignored) {}
    try { mux.release(); } catch (Throwable ignored) {}
    ex.release();
    if (cancelRequested) throw new IllegalStateException("cancelled");
    cb.progress(100);
    AppLog.i("Wallpaper", "transcode done path=" + dst.getAbsolutePath());
  }

  private static int even(int v) {
    return Math.max(2, v & ~1);
  }

  /** YUV420 就近缩放（Y + UV 两平面）。 */
  private static void scaleYuv(ByteBuffer src, int outW, int outH, ByteBuffer dst, int srcW, int srcH) {
    int srcY = srcW * srcH;
    int dstY = outW * outH;
    for (int y = 0; y < outH; y++) {
      int sy = y * srcH / outH;
      for (int x = 0; x < outW; x++) {
        int sx = x * srcW / outW;
        dst.put(y * outW + x, src.get(sy * srcW + sx));
      }
    }
    int sw = srcW / 2, sh = srcH / 2, dw = outW / 2, dh = outH / 2;
    for (int y = 0; y < dh; y++) {
      int sy = y * sh / dh;
      for (int x = 0; x < dw; x++) {
        int sx = x * sw / dw;
        int s = srcY + (sy * sw + sx) * 2;
        int d = dstY + (y * dw + x) * 2;
        dst.put(d, src.get(s));
        dst.put(d + 1, src.get(s + 1));
      }
    }
  }
}
