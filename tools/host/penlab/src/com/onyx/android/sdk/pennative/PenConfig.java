package com.onyx.android.sdk.pennative;

/**
 * Interop declaration: the pen configuration the firmware's pen-ink library reads field by field (JNI GetFloatField
 * and the like), so the field names and types are fixed. Penlab host tool only.
 */
public class PenConfig implements java.io.Serializable {
    public float brushAngle;
    public int brushShape;
    public boolean directionEnabled;
    public float endThinningRate;
    public float endVelocitySensitivity;
    public boolean fastMode;
    public float ignorePressure;
    public float minWidth;
    public float pressureSensitivity;
    public int rotateAngle;
    public float startLengthLimit;
    public float startPointLimit;
    public boolean tiltEnabled;
    public float tiltScale;
    public int type;
    public float velocityAmplifier;
    public float velocityIgnoreThreshold;
    public float velocityLowerBound;
    public float velocitySensitivity;
    public float velocityUpperBound;
    public int color = 0xFF000000;
    public float width = 3f;
    public float maxTouchPressure = 1f;
    public float dpi = 320f;
    public float displayScaleX = 1f;
    public float displayScaleY = 1f;
    public float scalePrecision = 1f;
    public float brushSpacing = 0.25f;
    public float brushRatio = 5f;
    public float smoothLevel = 0.2f;
}
