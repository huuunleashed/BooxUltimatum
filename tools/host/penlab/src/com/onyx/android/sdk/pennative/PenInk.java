package com.onyx.android.sdk.pennative;

import android.graphics.Bitmap;

/** Interop declaration: one batch of ink from the firmware's pen-ink library. Penlab host tool only. */
public final class PenInk {
    public final float[] points;
    public final int[] pointSizeArray;
    public final Bitmap[] bitmaps;

    public PenInk(float[] points, int[] pointSizeArray, Bitmap[] bitmaps) {
        this.points = points;
        this.pointSizeArray = pointSizeArray;
        this.bitmaps = bitmaps;
    }

    public static PenInk create(float[] points, int[] pointSizeArray, Bitmap[] bitmaps) {
        return new PenInk(points, pointSizeArray, bitmaps);
    }
}
