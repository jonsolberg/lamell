package se.jonsolberg.lamell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.FloatBuffer;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Offline mushroom image recognition: runs the bundled Danish Fungi 2020 model
 * (assets/model/fungi.onnx, 1604 species) with ONNX Runtime on the phone.
 * Nothing is sent anywhere.
 *
 * Part of Lamell. Licensed under the GNU General Public License v3.
 * The model weights themselves are CC BY-NC 4.0 (Picek et al.).
 */
final class Classifier {
    private static Classifier instance;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final int size;
    private final boolean squash;
    private final float cropPct;
    private final float[] mean = new float[3];
    private final float[] std = new float[3];

    static synchronized Classifier get(Context context) throws Exception {
        if (instance == null) instance = new Classifier(context.getApplicationContext());
        return instance;
    }

    static boolean available(Context context) {
        try {
            String[] files = context.getAssets().list("model");
            if (files == null) return false;
            for (String f : files) if (f.equals("fungi.onnx")) return true;
        } catch (Exception ignored) { }
        return false;
    }

    private Classifier(Context context) throws Exception {
        JSONObject meta = new JSONObject(Files.readAsset(context, "model/meta.json"));
        size = meta.getInt("size");
        squash = "squash".equals(meta.optString("mode", "squash"));
        cropPct = (float) meta.optDouble("crop_pct", 1.0);
        JSONArray m = meta.getJSONArray("mean"), s = meta.getJSONArray("std");
        for (int i = 0; i < 3; i++) {
            mean[i] = (float) m.getDouble(i);
            std[i] = (float) s.getDouble(i);
        }

        // ONNX Runtime wants a file path or a byte array; a file keeps the 50 MB out of the Java heap.
        File model = new File(context.getNoBackupFilesDir(), "fungi.onnx");
        long assetLength;
        try (android.content.res.AssetFileDescriptor fd = context.getAssets().openFd("model/fungi.onnx")) {
            assetLength = fd.getLength();
        }
        if (!model.exists() || model.length() != assetLength) {
            File tmp = new File(model.getPath() + ".tmp");
            try (InputStream in = context.getAssets().open("model/fungi.onnx");
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(model)) throw new IllegalStateException("could not store the model");
        }

        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(model.getPath(), opts);
        inputName = session.getInputNames().iterator().next();
    }

    /** Probabilities for all classes (softmax), for one photo. */
    synchronized float[] classify(Bitmap photo) throws Exception {
        Bitmap input = prepare(photo);
        int[] px = new int[size * size];
        input.getPixels(px, 0, size, 0, 0, size, size);
        if (input != photo) input.recycle();

        int plane = size * size;
        FloatBuffer buf = FloatBuffer.allocate(3 * plane);
        float[] data = buf.array();
        for (int i = 0; i < plane; i++) {
            int c = px[i];
            data[i] = (((c >> 16) & 0xFF) / 255f - mean[0]) / std[0];
            data[plane + i] = (((c >> 8) & 0xFF) / 255f - mean[1]) / std[1];
            data[2 * plane + i] = ((c & 0xFF) / 255f - mean[2]) / std[2];
        }

        try (OnnxTensor tensor = OnnxTensor.createTensor(env, buf, new long[]{1, 3, size, size});
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
            float[] logits = ((float[][]) result.get(0).getValue())[0];
            return softmax(logits);
        }
    }

    /** Scales (and for "crop" models centre-crops) to the model's input size. */
    private Bitmap prepare(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        Rect from;
        if (squash) {
            from = new Rect(0, 0, src.getWidth(), src.getHeight());
        } else {
            int side = Math.round(Math.min(src.getWidth(), src.getHeight()) * cropPct);
            int left = (src.getWidth() - side) / 2, top = (src.getHeight() - side) / 2;
            from = new Rect(left, top, left + side, top + side);
        }
        canvas.drawBitmap(src, from, new Rect(0, 0, size, size), paint);
        return out;
    }

    private static float[] softmax(float[] x) {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : x) max = Math.max(max, v);
        double sum = 0;
        float[] p = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            p[i] = (float) Math.exp(x[i] - max);
            sum += p[i];
        }
        for (int i = 0; i < p.length; i++) p[i] /= (float) sum;
        return p;
    }

    /** The n most likely classes as JSON: [{"i":index,"p":probability}, ...]. */
    static JSONArray top(float[] p, int n) throws Exception {
        Integer[] idx = new Integer[p.length];
        for (int i = 0; i < p.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Float.compare(p[b], p[a]));
        JSONArray out = new JSONArray();
        for (int k = 0; k < Math.min(n, idx.length); k++) {
            out.put(new JSONObject().put("i", idx[k]).put("p", (double) p[idx[k]]));
        }
        return out;
    }
}
