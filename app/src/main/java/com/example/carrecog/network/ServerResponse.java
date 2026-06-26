package com.example.carrecog.network;

import com.google.gson.annotations.SerializedName;

/**
 * Отговор от сървъра след изпращане на детекция.
 */
public class ServerResponse {

    @SerializedName("status")
    public String status;       // "received" | "error"

    @SerializedName("detection_id")
    public String detectionId;
}
