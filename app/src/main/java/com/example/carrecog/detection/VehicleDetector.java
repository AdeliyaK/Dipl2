package com.example.carrecog.detection;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * On-device детектор на превозни средства чрез TensorFlow Lite.
 * Зарежда YOLOv8n/YOLOv10n INT8 quantized модел от assets/model.tflite.
 *
 * Очаквана форма на изходния тензор: [1, 84, 8400]
 *   - Ред 0-3: cx, cy, w, h (в пиксели спрямо INPUT_SIZE=640)
 *   - Ред 4-83: scores за 80-те COCO класа
 *   - 8400 = брой anchor точки
 */
public class VehicleDetector {

    private static final String TAG = "VehicleDetector";
    private static final String MODEL_FILE = "model.tflite";
    private static final int INPUT_SIZE = 640;
    private static final int NUM_THREADS = 4;
    private static final float NMS_IOU_THRESHOLD = 0.45f;

    // COCO class IDs, класифицирани като "превозно средство"
    private static final int[] VEHICLE_CLASS_IDS = {2, 3, 5, 7}; // car, motorcycle, bus, truck

    private final Interpreter interpreter;
    private volatile float confidenceThreshold;

    private final ImageProcessor imageProcessor;

    public VehicleDetector(Context context, float confidenceThreshold) throws IOException {
        this.confidenceThreshold = confidenceThreshold;

        MappedByteBuffer modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE);

        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(NUM_THREADS);
        interpreter = new Interpreter(modelBuffer, options);

        // Мащабираме входния кадър до изискания INPUT_SIZE
        imageProcessor = new ImageProcessor.Builder()
                .add(new ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
                .build();

        Log.i(TAG, "TFLite детекторът е инициализиран. Вход: " + INPUT_SIZE + "x" + INPUT_SIZE);
    }

    /**
     * Изпълнява inference върху един кадър от камерата.
     *
     * @param bitmap  Входен кадър (произволни размери — автоматично се мащабира)
     * @return Списък с детекции след NMS, сортирани по confidence (низходящо)
     */
    public List<DetectionResult> detect(Bitmap bitmap) {
        // Подготвяме входния тензор
        TensorImage tensorImage = new TensorImage();
        tensorImage.load(bitmap);
        tensorImage = imageProcessor.process(tensorImage);

        // Изходният тензор за YOLOv8n: [1, 84, 8400]
        int[] outputShape = interpreter.getOutputTensor(0).shape();
        int numFeatures = outputShape[1]; // 84 = 4 box + 80 classes
        int numAnchors  = outputShape[2]; // 8400

        float[][][] output = new float[1][numFeatures][numAnchors];
        interpreter.run(tensorImage.getBuffer(), output);

        return parseAndFilter(output[0], numFeatures - 4, numAnchors);
    }

    /**
     * Извлича bounding boxes, филтрира по клас и confidence, прилага NMS.
     *
     * @param raw         Суров изход [numFeatures][numAnchors]
     * @param numClasses  Брой класа (обикновено 80 за COCO)
     * @param numAnchors  Брой anchor точки (обикновено 8400)
     */
    private List<DetectionResult> parseAndFilter(float[][] raw, int numClasses, int numAnchors) {
        List<DetectionResult> candidates = new ArrayList<>();

        for (int i = 0; i < numAnchors; i++) {
            // Координати в пиксели спрямо INPUT_SIZE (center format)
            float cx = raw[0][i];
            float cy = raw[1][i];
            float w  = raw[2][i];
            float h  = raw[3][i];

            // Намираме класа с максимален score
            float maxScore = 0f;
            int bestClass = -1;
            for (int c = 0; c < numClasses; c++) {
                float score = raw[4 + c][i];
                if (score > maxScore) {
                    maxScore = score;
                    bestClass = c;
                }
            }

            if (maxScore < confidenceThreshold) continue;
            if (!isVehicleClass(bestClass)) continue;

            // Конвертираме от center формат в corner формат, нормализирано [0,1]
            RectF box = new RectF(
                    Math.max(0f, (cx - w / 2f) / INPUT_SIZE),
                    Math.max(0f, (cy - h / 2f) / INPUT_SIZE),
                    Math.min(1f, (cx + w / 2f) / INPUT_SIZE),
                    Math.min(1f, (cy + h / 2f) / INPUT_SIZE)
            );

            candidates.add(new DetectionResult(box, maxScore, bestClass));
        }

        return applyNMS(candidates);
    }

    /**
     * Non-Maximum Suppression: премахва припокриващи се bounding boxes.
     * Запазва само детекцията с най-висок confidence при IOU > NMS_IOU_THRESHOLD.
     */
    private List<DetectionResult> applyNMS(List<DetectionResult> detections) {
        // Сортиране по confidence (низходящо)
        detections.sort((a, b) -> Float.compare(b.confidence, a.confidence));

        boolean[] suppressed = new boolean[detections.size()];
        List<DetectionResult> results = new ArrayList<>();

        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i]) continue;
            results.add(detections.get(i));
            for (int j = i + 1; j < detections.size(); j++) {
                if (suppressed[j]) continue;
                if (computeIOU(detections.get(i).boundingBox, detections.get(j).boundingBox) > NMS_IOU_THRESHOLD) {
                    suppressed[j] = true;
                }
            }
        }

        return results;
    }

    /** Изчислява Intersection over Union между два нормализирани правоъгълника. */
    private float computeIOU(RectF a, RectF b) {
        float interLeft   = Math.max(a.left, b.left);
        float interTop    = Math.max(a.top,  b.top);
        float interRight  = Math.min(a.right, b.right);
        float interBottom = Math.min(a.bottom, b.bottom);

        float intersection = Math.max(0f, interRight - interLeft)
                           * Math.max(0f, interBottom - interTop);
        if (intersection == 0f) return 0f;

        float areaA = (a.right - a.left) * (a.bottom - a.top);
        float areaB = (b.right - b.left) * (b.bottom - b.top);
        float union = areaA + areaB - intersection;

        return union > 0f ? intersection / union : 0f;
    }

    private boolean isVehicleClass(int classId) {
        for (int id : VEHICLE_CLASS_IDS) {
            if (id == classId) return true;
        }
        return false;
    }

    public void setConfidenceThreshold(float threshold) {
        this.confidenceThreshold = threshold;
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
        }
    }
}
