package com.onyx.android.sdk.pennative;

/**
 * Interop declaration for the firmware's pen-ink library (`/system/lib64/libneopen_jni.so`), used only by the
 * penlab host tool to measure BOOX's own ink on the owner's tablet. The library binds its JNI functions to this class
 * name, so the name and the native signatures must be these; nothing here is BOOX code. Never bundled in an app.
 */
public final class NeoPenNative {
    public static final NeoPenNative INSTANCE = new NeoPenNative();

    static {
        System.loadLibrary("neopen_jni");
    }

    private NeoPenNative() {
    }

    public native long nativeCreateLogger();

    public native long nativeCreatePen(int penType, PenConfig config);

    public native void nativeDestroyPen(long pen);

    /** Each point is seven doubles: x, y, pressure 0..1, size, tilt x, tilt y (degrees), time (ms). */
    public native PenInkResult nativeOnPenDown(long pen, double[] point, boolean repaint);

    public native PenInkResult nativeOnPenMove(long pen, double[] points, double[] prediction, boolean repaint);

    public native PenInkResult nativeOnPenUp(long pen, double[] point, boolean repaint);

    public native void nativeRegisterLogger(long logger);

    public native void nativeSetLogLevel(int level);
}
