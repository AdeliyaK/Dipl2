package com.example.carrecog.ui;

/**
 * Елемент в лога на засичанията (показва се в RecyclerView).
 */
public class DetectionLogItem {

    public final String trackerId;
    public final String time;       // HH:mm:ss
    public String status;           // "Изпраща...", "Успешно", "Грешка"

    public DetectionLogItem(String trackerId, String time, String status) {
        this.trackerId = trackerId;
        this.time = time;
        this.status = status;
    }
}
