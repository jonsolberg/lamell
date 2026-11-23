package se.jonsolberg.lamell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * File helpers for Lamell: photos of finds live in files/photos as JPEG, the
 * list of finds in files/finds.json.
 *
 * Part of Lamell. Licensed under the GNU General Public License v3.
 */
final class Files {
    private Files() {}

    static String readAsset(Context c, String path) throws Exception {
        try (InputStream in = c.getAssets().open(path)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 14];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static File photoDir(Context c) {
        File d = new File(c.getFilesDir(), "photos");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static String readText(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Writes via a temporary file so a crash never leaves half a file behind. */
    static boolean writeText(File f, String text) {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
        return tmp.renameTo(f);
    }

    /**
     * Decodes a photo, turned the right way up (EXIF) and scaled so the longest
     * side is at most maxSide pixels.
     */
    static Bitmap loadPhoto(Context c, Uri uri, int maxSide) throws Exception {
        byte[] bytes;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("cannot open " + uri);
            bytes = readAll(in);
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (bmp == null) throw new IllegalStateException("not an image");

        int rotation = 0;
        try {
            ExifInterface exif = new ExifInterface(new java.io.ByteArrayInputStream(bytes));
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                default: break;
            }
        } catch (Exception ignored) { }

        float scale = Math.min(1f, maxSide / (float) Math.max(bmp.getWidth(), bmp.getHeight()));
        if (rotation == 0 && scale >= 1f) return bmp;
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        m.postRotate(rotation);
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (out != bmp) bmp.recycle();
        return out;
    }

    static void saveJpeg(Bitmap bmp, File f, int quality) throws Exception {
        try (OutputStream out = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
        }
    }

    /** Copies a stored photo into the cache so it can be shared. */
    static File copyToShare(Context c, File src, String name) throws Exception {
        File dir = new File(c.getCacheDir(), "share");
        if (!dir.isDirectory()) dir.mkdirs();
        File dst = new File(dir, name);
        try (InputStream in = new java.io.FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return dst;
    }
}
