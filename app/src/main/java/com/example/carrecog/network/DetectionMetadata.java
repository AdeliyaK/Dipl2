package com.example.carrecog.network;

import com.google.gson.annotations.SerializedName;

/**
 * JSON метаданни, изпращани към сървъра при всяко ново засичане.
 * Следва API контракта: POST /api/v1/detections (multipart/form-data).
 */
public class DetectionMetadata {

    @SerializedName("tracker_id")
    public final String trackerId;

    @SerializedName("timestamp")
    public final String timestamp;       // ISO 8601 формат

    @SerializedName("device_lat")
    public final double deviceLat;

    @SerializedName("device_lon")
    public final double deviceLon;

    @SerializedName("device_speed_kmh")
    public final float deviceSpeedKmh;

    @SerializedName("device_bearing_deg")
    public final float deviceBearingDeg;

    public DetectionMetadata(String trackerId, String timestamp,
                              double deviceLat, double deviceLon,
                              float deviceSpeedKmh, float deviceBearingDeg) {
        this.trackerId = trackerId;
        this.timestamp = timestamp;
        this.deviceLat = deviceLat;
        this.deviceLon = deviceLon;
        this.deviceSpeedKmh = deviceSpeedKmh;
        this.deviceBearingDeg = deviceBearingDeg;
    }
}
