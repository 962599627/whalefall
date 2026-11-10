package com.whalefall.movie;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Movie;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;

/**
 * 1.9.1 图片全屏查看：玻璃黑底、双指缩放/拖动、单击关闭；GIF（API28+ ImageDecoder，低版 Movie）。
 */
final class ImageViewerScreen {
    private ImageViewerScreen() {}

    static void show(final MainActivity a, final File file) {
        if (file == null || !file.isFile()) {
            a.toast("图片不存在");
            return;
        }
        AppLog.i("ImageViewer", "open file=" + file.getName() + " size=" + file.length());
        a.renderPage("查看图片", "IMAGE");
        if (a.root != null) a.root.setBackgroundColor(android.graphics.Color.BLACK);
        if (a.scroll != null) a.scroll.setBackgroundColor(android.graphics.Color.BLACK);
        if (a.body == null) return;
        a.body.setBackgroundColor(android.graphics.Color.BLACK);
        a.body.setTag("image-viewer");
        TextView title = a.text(file.getName(), 14, UiTokens.TEXT_PRIMARY);
        title.setGravity(Gravity.CENTER);
        title.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(6));
        a.body.addView(title);
        final FrameLayout stage = new FrameLayout(a);
        stage.setBackgroundColor(android.graphics.Color.BLACK);
        a.body.addView(stage, new LinearLayout.LayoutParams(-1, a.dp(560)));

        final ImageView image = new ImageView(a);
        image.setScaleType(ImageView.ScaleType.MATRIX);
        image.setAdjustViewBounds(false);
        stage.addView(image, new FrameLayout.LayoutParams(-1, -1));

        final Matrix matrix = new Matrix();
        final float[] scale = {1f};
        final float[] trans = {0f, 0f};
        image.setImageMatrix(matrix);
        image.setTag("image-viewer-surface");

        final String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        boolean gif = name.endsWith(".gif");
        if (gif) {
            showGif(a, stage, image, file);
        } else {
            loadBitmap(a, image, file);
        }

        final ScaleGestureDetector gesture =
                new ScaleGestureDetector(
                        a,
                        new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                            @Override
                            public boolean onScale(ScaleGestureDetector detector) {
                                scale[0] = Math.max(1f, Math.min(5f, scale[0] * detector.getScaleFactor()));
                                apply(image, matrix, scale[0], trans[0], trans[1]);
                                return true;
                            }
                        });
        final GestureDetector tap =
                new GestureDetector(
                        a,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onSingleTapConfirmed(MotionEvent e) {
                                FileManagerScreen.resume(a);
                                return true;
                            }
                        });
        // 拖动：双指缩放后的单指移动
        final float[] last = {0f, 0f};
        final boolean[] dragging = {false};
        image.setOnTouchListener(
                (v, event) -> {
                    gesture.onTouchEvent(event);
                    tap.onTouchEvent(event);
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            last[0] = event.getX();
                            last[1] = event.getY();
                            dragging[0] = scale[0] > 1f;
                            break;
                        case MotionEvent.ACTION_MOVE:
                            if (dragging[0] && !gesture.isInProgress()) {
                                trans[0] += event.getX() - last[0];
                                trans[1] += event.getY() - last[1];
                                last[0] = event.getX();
                                last[1] = event.getY();
                                apply(image, matrix, scale[0], trans[0], trans[1]);
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            dragging[0] = false;
                            break;
                        default:
                            break;
                    }
                    return true;
                });
    }

    private static void loadBitmap(MainActivity a, final ImageView image, File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            int sample = 1;
            while (options.outWidth / sample > 2400 || options.outHeight / sample > 2400) sample *= 2;
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), decode);
            if (bitmap == null) {
                a.toast("无法识别该图片格式");
                return;
            }
            image.setImageBitmap(bitmap);
        } catch (Throwable error) {
            AppLog.i("ImageViewer", "decode error=" + error.getClass().getSimpleName());
            a.toast("图片加载失败");
        }
    }

    /** GIF 动画：API28+ 用 ImageDecoder，否则 Movie 自绘。 */
    private static void showGif(
            MainActivity a, FrameLayout stage, ImageView image, File file) {
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                android.graphics.ImageDecoder.Source source =
                        android.graphics.ImageDecoder.createSource(file);
                Drawable drawable =
                        android.graphics.ImageDecoder.decodeDrawable(
                                source,
                                (decoder, info, src) ->
                                        decoder.setTargetSize(
                                                Math.min(info.getSize().getWidth(), 1600),
                                                (int)
                                                        (info.getSize().getHeight()
                                                                * Math.min(
                                                                        1f,
                                                                        1600f
                                                                                / Math.max(
                                                                                        1,
                                                                                        info.getSize()
                                                                                                .getWidth())))));
                image.setImageDrawable(drawable);
                if (drawable instanceof AnimatedImageDrawable)
                    ((AnimatedImageDrawable) drawable).start();
                return;
            } catch (Throwable error) {
                AppLog.i("ImageViewer", "gif ImageDecoder error=" + error.getClass().getSimpleName());
            }
        }
        // API<28：Movie 自己播放
        try {
            final Movie movie = Movie.decodeFile(file.getAbsolutePath());
            if (movie == null) {
                loadBitmap(a, image, file);
                return;
            }
            final android.view.View canvasView =
                    new android.view.View(a) {
                        @Override
                        protected void onDraw(android.graphics.Canvas canvas) {
                            long now = android.os.SystemClock.uptimeMillis() % Math.max(1, movie.duration());
                            movie.setTime((int) now);
                            float sx = getWidth() / (float) Math.max(1, movie.width());
                            float sy = getHeight() / (float) Math.max(1, movie.height());
                            canvas.save();
                            canvas.scale(sx, sy);
                            movie.draw(canvas, 0, 0);
                            canvas.restore();
                            invalidate();
                        }
                    };
            stage.addView(canvasView, new FrameLayout.LayoutParams(-1, -1));
            image.setVisibility(android.view.View.GONE);
        } catch (Throwable error) {
            loadBitmap(a, image, file);
        }
    }

    private static void apply(ImageView image, Matrix matrix, float scale, float dx, float dy) {
        matrix.reset();
        matrix.postScale(scale, scale, image.getWidth() / 2f, image.getHeight() / 2f);
        matrix.postTranslate(dx, dy);
        image.setImageMatrix(matrix);
        image.invalidate();
    }
}
