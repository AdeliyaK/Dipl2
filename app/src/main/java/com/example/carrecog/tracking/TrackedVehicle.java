package com.example.carrecog.tracking;

import android.graphics.RectF;

/**
 * Представлява едно проследявано превозно средство с временен локален ID.
 * Обектът живее в паметта докато превозното средство е видимо в кадъра.
 */
public class TrackedVehicle {

    /** Уникален идентификатор, генериран при първото засичане. */
    public final String trackerId;

    /** Последна известна позиция (нормализирани координати [0,1]). */
    public RectF boundingBox;

    /** Брой последователни кадри без засичане (използва се за изтриване). */
    public int missedFrames;

    /** Времеви маркер на последното изпращане към сървъра (Unix ms). */
    public long lastSentTimestamp;

    /** Дали е изпратено поне веднъж. */
    public boolean hasSentToServer;

    public TrackedVehicle(String trackerId, RectF boundingBox) {
        this.trackerId = trackerId;
        this.boundingBox = boundingBox;
        this.missedFrames = 0;
        this.lastSentTimestamp = 0L;
        this.hasSentToServer = false;
    }
}
