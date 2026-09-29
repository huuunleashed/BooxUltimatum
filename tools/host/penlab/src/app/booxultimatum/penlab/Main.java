package app.booxultimatum.penlab;

import android.graphics.Bitmap;
import com.onyx.android.sdk.pennative.NeoPenNative;
import com.onyx.android.sdk.pennative.PenConfig;
import com.onyx.android.sdk.pennative.PenInk;
import com.onyx.android.sdk.pennative.PenInkResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Penlab: feeds strokes to the firmware's pen-ink library on the tablet and writes what it returns, so BooxUltimatum's
 * own brushes can be measured against BOOX's. A developer tool run from the shell (`app_process`), never part of an app.
 *
 * Script, one command per line ('#' starts a comment):
 *   pen TYPE [field=value ...]   a new pen (TYPE as the library numbers them) with PenConfig fields
 *   point X Y PRESSURE SIZE TILTX TILTY TIME
 *   run NAME [batch=N]           feeds the points given since the last run: down, moves in batches of N, up
 * Output: "ink NAME PHASE real|prediction n=… sizes=…" lines followed by a "data" line with the floats, and
 * "bitmap NAME INDEX W H FILE" lines with PNGs written to OUTDIR.
 */
public final class Main {
    private static final Locale L = Locale.ROOT;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: Main SCRIPT OUTDIR");
            System.exit(2);
        }
        File outDir = new File(args[1]);
        outDir.mkdirs();
        PrintStream out = System.out;
        NeoPenNative n = NeoPenNative.INSTANCE;
        PenConfig config = null;
        int type = 0;
        List<double[]> points = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(args[0]))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] t = line.split("\\s+");
                switch (t[0]) {
                    case "pen": {
                        type = Integer.parseInt(t[1]);
                        config = new PenConfig();
                        config.type = type;
                        for (int i = 2; i < t.length; i++) set(config, t[i]);
                        points.clear();
                        break;
                    }
                    case "point": {
                        double[] p = new double[7];
                        for (int i = 0; i < 7; i++) p[i] = Double.parseDouble(t[i + 1]);
                        points.add(p);
                        break;
                    }
                    case "run": {
                        String name = t[1];
                        int batch = 1;
                        for (int i = 2; i < t.length; i++) if (t[i].startsWith("batch=")) batch = Integer.parseInt(t[i].substring(6));
                        run(n, type, config, points, name, batch, outDir, out);
                        points.clear();
                        break;
                    }
                    default:
                        out.println("error unknown command " + t[0]);
                }
            }
        }
        out.println("done");
    }

    private static void run(NeoPenNative n, int type, PenConfig config, List<double[]> points, String name, int batch, File outDir, PrintStream out) throws Exception {
        long pen = n.nativeCreatePen(type, config);
        out.println(String.format(L, "pen %s type=%d handle=%d points=%d", name, type, pen, points.size()));
        if (pen == 0L || points.size() < 2) return;
        int[] counter = {0};
        long t0 = System.nanoTime();
        dump(n.nativeOnPenDown(pen, points.get(0), true), name, "down", outDir, out, counter);
        int i = 1;
        while (i < points.size() - 1) {
            int end = Math.min(points.size() - 1, i + batch);
            double[] flat = new double[(end - i) * 7];
            for (int k = i; k < end; k++) System.arraycopy(points.get(k), 0, flat, (k - i) * 7, 7);
            dump(n.nativeOnPenMove(pen, flat, null, true), name, "move", outDir, out, counter);
            i = end;
        }
        dump(n.nativeOnPenUp(pen, points.get(points.size() - 1), true), name, "up", outDir, out, counter);
        out.println(String.format(L, "time %s us=%d", name, (System.nanoTime() - t0) / 1000));
        n.nativeDestroyPen(pen);
    }

    private static void dump(PenInkResult r, String name, String phase, File outDir, PrintStream out, int[] counter) throws Exception {
        if (r == null) { out.println("ink " + name + " " + phase + " null"); return; }
        dumpInk(r.realInk, name, phase, "real", outDir, out, counter);
    }

    private static void dumpInk(PenInk ink, String name, String phase, String kind, File outDir, PrintStream out, int[] counter) throws Exception {
        if (ink == null) return;
        StringBuilder sizes = new StringBuilder();
        if (ink.pointSizeArray != null) for (int s : ink.pointSizeArray) sizes.append(s).append(',');
        out.println(String.format(L, "ink %s %s %s n=%d sizes=%s bitmaps=%d", name, phase, kind,
            ink.points == null ? 0 : ink.points.length, sizes, ink.bitmaps == null ? 0 : ink.bitmaps.length));
        if (ink.points != null && ink.points.length > 0) {
            StringBuilder d = new StringBuilder("data");
            for (float f : ink.points) d.append(' ').append(String.format(L, "%.4f", f));
            out.println(d);
        }
        if (ink.bitmaps != null) {
            for (Bitmap b : ink.bitmaps) {
                if (b == null) { out.println("bitmap " + name + " null"); continue; }
                int idx = counter[0]++;
                String file = name + "_" + idx + ".png";
                if (idx < 400) {
                    try (FileOutputStream fo = new FileOutputStream(new File(outDir, file))) {
                        b.compress(Bitmap.CompressFormat.PNG, 100, fo);
                    }
                }
                out.println(String.format(L, "bitmap %s %d %d %d %s", name, idx, b.getWidth(), b.getHeight(), idx < 400 ? file : "-"));
            }
        }
    }

    private static void set(PenConfig c, String kv) throws Exception {
        int eq = kv.indexOf('=');
        String k = kv.substring(0, eq), v = kv.substring(eq + 1);
        Field f = PenConfig.class.getField(k);
        Class<?> ft = f.getType();
        if (ft == float.class) f.setFloat(c, Float.parseFloat(v));
        else if (ft == int.class) f.setInt(c, v.startsWith("0x") ? (int) Long.parseLong(v.substring(2), 16) : Integer.parseInt(v));
        else if (ft == boolean.class) f.setBoolean(c, Boolean.parseBoolean(v) || v.equals("1"));
    }
}
