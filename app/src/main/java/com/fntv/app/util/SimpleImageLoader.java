package com.fntv.app.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.util.LruCache;
import android.util.Log;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 简易图片加载器 - 使用 OkHttp（自动经过 AuthInterceptor 签名）
 * 兼容 Android 4.4+
 */
public class SimpleImageLoader {

    private static final String TAG = "ImageLoader";
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    /** 加载图片（使用 OkHttpClient，自动携带认证头） */
    public static void load(String url, ImageView view, OkHttpClient client) {
        if (url == null || url.isEmpty()) {
            view.setImageBitmap(null);
            view.setBackgroundColor(0xFF333333);
            return;
        }

        Bitmap cached = cache.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }

        // Android 4.4 AsyncTask 默认串行，用线程池实现并行下载
        new ImageLoadTask(view, client).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, url);
    }

    public static void clearCache() {
        cache.evictAll();
    }

    /** 加载模糊背景图：按 ~24px 宽降采样解码，ImageView 放大时双线性插值天然糊——用于只有竖版海报时横铺 hero 背景（Plex 式兜底） */
    public static void loadBlur(String url, ImageView view, OkHttpClient client) {
        if (url == null || url.isEmpty()) {
            view.setImageBitmap(null);
            view.setBackgroundColor(0xFF222222);
            return;
        }
        final String key = url + "#blur";
        Bitmap cached = cache.get(key);
        if (cached != null) {
            view.setScaleType(ImageView.ScaleType.FIT_XY);
            view.setImageBitmap(cached);
            return;
        }
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        new BlurLoadTask(view, client).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, url);
    }

    private static class BlurLoadTask extends AsyncTask<String, Void, Bitmap> {
        private final ImageView imageView;
        private final OkHttpClient client;

        BlurLoadTask(ImageView imageView, OkHttpClient client) {
            this.imageView = imageView;
            this.client = client;
        }

        @Override
        protected Bitmap doInBackground(String... params) {
            try {
                Request request = new Request.Builder().url(params[0]).build();
                Response response = client.newCall(request).execute();
                if (response.code() != 200 || response.body() == null) return null;
                InputStream input = response.body().byteStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = input.read(buf)) != -1) baos.write(buf, 0, n);
                input.close();
                byte[] data = baos.toByteArray();

                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                o.inSampleSize = Math.max(1, Math.max(bounds.outWidth / 24, bounds.outHeight / 14));
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (bmp != null) cache.put(params[0] + "#blur", bmp);
                return bmp;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap bmp) {
            if (bmp != null) {
                imageView.setScaleType(ImageView.ScaleType.FIT_XY);
                imageView.setImageBitmap(bmp);
            } else {
                imageView.setBackgroundColor(0xFF222222);
            }
        }
    }

    private static class ImageLoadTask extends AsyncTask<String, Void, Bitmap> {
        private final ImageView imageView;
        private final OkHttpClient client;

        ImageLoadTask(ImageView imageView, OkHttpClient client) {
            this.imageView = imageView;
            this.client = client;
        }

        @Override
        protected Bitmap doInBackground(String... params) {
            String urlStr = params[0];
            try {
                Request request = new Request.Builder()
                        .url(urlStr)
                        .build();

                Response response = client.newCall(request).execute();
                int code = response.code();
                String contentType = response.header("Content-Type");
                long len = response.body() != null ? response.body().contentLength() : -1;
                // Log.d(TAG, "HTTP " + code + " type=" + contentType + " len=" + len + " " + urlStr);

                if (code != 200) return null;

                // 不是图片就打印并跳过
                if (contentType != null && !contentType.contains("image")) {
                    String body = response.body() != null ? response.body().string() : "";
                    String snippet = body.length() > 200 ? body.substring(0, 200) : body;
                    Log.w(TAG, "NOT_IMAGE: " + snippet);
                    return null;
                }

                // 读取图片字节
                InputStream input = response.body().byteStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = input.read(buf)) != -1) baos.write(buf, 0, n);
                input.close();
                byte[] imageData = baos.toByteArray();

                // 三重解码尝试
                Bitmap bitmap = tryDecode(imageData);

                if (bitmap != null) {
                    cache.put(urlStr, bitmap);
                    Log.d(TAG, "OK (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
                } else {
                    Log.e(TAG, "DECODE_FAILED");
                }
                return bitmap;
            } catch (Exception e) {
                Log.e(TAG, "FAIL: " + e.getMessage());
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap);
            } else {
                imageView.setBackgroundColor(0xFF444444);
            }
        }
    }

    private static Bitmap tryDecode(byte[] data) {
        Bitmap bmp = null;
        if (bmp == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inPurgeable = true;
            o.inInputShareable = true;
            try { bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o); } catch (Exception ignored) {}
        }
        if (bmp == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inPurgeable = true;
            o.inInputShareable = true;
            try { bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o); } catch (Exception ignored) {}
        }
        if (bmp == null) {
            try { bmp = BitmapFactory.decodeByteArray(data, 0, data.length); } catch (Exception ignored) {}
        }
        return bmp;
    }
}
