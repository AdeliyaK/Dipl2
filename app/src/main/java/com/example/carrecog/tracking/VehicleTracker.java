package com.example.carrecog.tracking;

import android.graphics.RectF;

import com.example.carrecog.detection.DetectionResult;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Лек IOU-базиран локален tracker между последователни кадри.
 *
 * За всяко засечено превозно средство се присвоява уникален временен ID.
 * Tracker-ът следи кои обекти са нови (за изпращане) и кои вече са познати.
 * Логиката за повторно изпращане (след напускане и повторно влизане)
 * се управлява от shouldSendToServer().
 */
public class VehicleTracker {

    // Минимален IOU за да се счита, че детекция съвпада с проследяван обект
    private static final float IOU_MATCH_THRESHOLD = 0.30f;

    // Брой кадри без засичане преди обектът да се счита за изгубен
    private static final int MAX_MISSED_FRAMES = 12;

    // Минимален интервал за повторно изпращане на един и същ обект (мс)
    private static final long RESEND_INTERVAL_MS = 30_000L;

    private final List<TrackedVehicle> activeVehicles = new ArrayList<>();

    public interface TrackingListener {
        void onNewVehicle(TrackedVehicle vehicle);
        void onVehicleLost(TrackedVehicle vehicle);
    }

    private TrackingListener listener;

    public void setTrackingListener(TrackingListener listener) {
        this.listener = listener;
    }

    /**
     * Обновява tracker-а с новите детекции от текущия кадър.
     * Прилага greedy matching по IOU между детекции и активни обекти.
     *
     * @param detections Детекции от текущия кадър
     * @return Текущ списък с активно проследявани превозни средства
     */
    public List<TrackedVehicle> update(List<DetectionResult> detections) {
        boolean[] detectionMatched = new boolean[detections.size()];

        // Фаза 1: свързване на съществуващи обекти с детекции
        for (TrackedVehicle tracked : activeVehicles) {
            float bestIou = IOU_MATCH_THRESHOLD;
            int bestIdx = -1;

            for (int i = 0; i < detections.size(); i++) {
                if (detectionMatched[i]) continue;
                float iou = computeIOU(tracked.boundingBox, detections.get(i).boundingBox);
                if (iou > bestIou) {
                    bestIou = iou;
                    bestIdx = i;
                }
            }

            if (bestIdx >= 0) {
                // Обновяваме позицията на проследявания обект
                tracked.boundingBox = new RectF(detections.get(bestIdx).boundingBox);
                tracked.missedFrames = 0;
                detectionMatched[bestIdx] = true;
            } else {
                tracked.missedFrames++;
            }
        }

        // Фаза 2: нови детекции = нови превозни средства
        for (int i = 0; i < detections.size(); i++) {
            if (!detectionMatched[i]) {
                TrackedVehicle newVehicle = new TrackedVehicle(
                        generateTrackerId(),
                        new RectF(detections.get(i).boundingBox));
                activeVehicles.add(newVehicle);
                if (listener != null) {
                    listener.onNewVehicle(newVehicle);
                }
            }
        }

        // Фаза 3: изтриване на обекти, изгубени за твърде много кадри
        Iterator<TrackedVehicle> it = activeVehicles.iterator();
        while (it.hasNext()) {
            TrackedVehicle v = it.next();
            if (v.missedFrames > MAX_MISSED_FRAMES) {
                if (listener != null) listener.onVehicleLost(v);
                it.remove();
            }
        }

        return new ArrayList<>(activeVehicles);
    }

    /**
     * Проверява дали превозното средство трябва да бъде изпратено към сървъра.
     * Изпраща при:
     *   1. Ново засичане (никога не е изпращано)
     *   2. Изтекъл RESEND_INTERVAL_MS (превозното средство е видимо дълго)
     */
    public boolean shouldSendToServer(TrackedVehicle vehicle) {
        if (!vehicle.hasSentToServer) return true;
        return (System.currentTimeMillis() - vehicle.lastSentTimestamp) >= RESEND_INTERVAL_MS;
    }

    /** Маркира обект като изпратен и записва времевия маркер. */
    public void markAsSent(TrackedVehicle vehicle) {
        vehicle.hasSentToServer = true;
        vehicle.lastSentTimestamp = System.currentTimeMillis();
    }

    private float computeIOU(RectF a, RectF b) {
        float interLeft   = Math.max(a.left, b.left);
        float interTop    = Math.max(a.top, b.top);
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

    /** Генерира кратък четим идентификатор от типа "V-3A7F1B2C". */
    private String generateTrackerId() {
        return "V-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
