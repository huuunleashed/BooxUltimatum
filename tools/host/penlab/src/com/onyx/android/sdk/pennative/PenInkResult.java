package com.onyx.android.sdk.pennative;

/** Interop declaration: the firmware pen-ink library's result, real ink and predicted ink. Penlab host tool only. */
public final class PenInkResult {
    public final PenInk realInk;
    public final PenInk predictionInk;

    public PenInkResult(PenInk realInk, PenInk predictionInk) {
        this.realInk = realInk;
        this.predictionInk = predictionInk;
    }

    public static PenInkResult create(PenInk realInk, PenInk predictionInk) {
        return new PenInkResult(realInk, predictionInk);
    }
}
