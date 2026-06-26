package com.example.carrecog.detection;

import android.graphics.RectF;

/**
 * Резултат от on-device детекция — bounding box и confidence score.
 * Координатите са нормализирани в диапазона [0, 1].
 */
public class DetectionResult {

    /** Нормализирани координати на засеченото превозно средство. */
    public final RectF boundingBox;

    /** Вероятност за принадлежност към клас "vehicle" (0.0 – 1.0). */
    public final float confidence;

    /** ID на класа по COCO (car=2, motorcycle=3, bus=5, truck=7). */
    public final int classId;

    public DetectionResult(RectF boundingBox, float confidence, int classId) {
        this.boundingBox = boundingBox;
        this.confidence = confidence;
        this.classId = classId;
    }
}
