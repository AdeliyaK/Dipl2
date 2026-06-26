package com.example.carrecog;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.carrecog.camera.CameraManager;
import com.example.carrecog.detection.DetectionResult;
import com.example.carrecog.detection.VehicleDetector;
import com.example.carrecog.location.LocationModule;
import com.example.carrecog.network.DetectionMetadata;
import com.example.carrecog.network.DetectionSender;
import com.example.carrecog.tracking.TrackedVehicle;
import com.example.carrecog.tracking.VehicleTracker;
import com.example.carrecog.ui.BoundingBoxOverlay;
import com.example.carrecog.ui.DetectionLogAdapter;
import com.example.carrecog.ui.DetectionLogItem;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Главен Activity — координира всички компоненти на системата.
 *
 * Жизнен цикъл на данните за един кадър:
 *   CameraManager → onNewFrame()
 *     ├─ detectionSender.bufferFrame()   [кадърът се буферира за изпращане]
 *     └─ inferenceExecutor               [ML inference на background thread]
 *           ├─ vehicleDetector.detect()  [TFLite → DetectionResult[]]
 *           ├─ vehicleTracker.update()   [IOU tracking → TrackedVehicle[]]
 *           ├─ overlayView.setVehicles() [UI update на main thread]
 *           └─ sendVehicleDetection()    [при ново засичане]
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
    };

    // ── Компоненти ────────────────────────────────────────────────────────────
    private CameraManager    cameraManager;
    private VehicleDetector  vehicleDetector;
    private VehicleTracker   vehicleTracker;
    private LocationModule   locationModule;
    private DetectionSender  detectionSender;

    // ── Последни GPS данни (volatile за thread safety) ────────────────────────
    private volatile double currentLat        = 0.0;
    private volatile double currentLon        = 0.0;
    private volatile float  currentSpeedKmh   = 0f;
    private volatile float  currentBearingDeg = 0f;

    // ── UI ────────────────────────────────────────────────────────────────────
    private PreviewView         previewView;
    private BoundingBoxOverlay  overlayView;
    private TextView            tvConnectionStatus;
    private DetectionLogAdapter logAdapter;

    // Main thread handler за UI актуализации от background threads
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Отделен thread за ML inference — не блокира camera thread
    private final ExecutorService inferenceExecutor = Executors.newSingleThreadExecutor();

    // ISO 8601 форматер (UTC)
    private final SimpleDateFormat isoFormat;

    {
        isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Инициализация на UI елементите
        previewView        = findViewById(R.id.preview_view);
        overlayView        = findViewById(R.id.bounding_box_overlay);
        tvConnectionStatus = findViewById(R.id.tv_connection_status);

        RecyclerView rvDetectionLog = findViewById(R.id.rv_detection_log);
        logAdapter = new DetectionLogAdapter();
        rvDetectionLog.setAdapter(logAdapter);
        rvDetectionLog.setLayoutManager(new LinearLayoutManager(this));

        ImageButton btnSettings = findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // Инициализация на tracker и sender (не изискват разрешения)
        vehicleTracker = new VehicleTracker();
        detectionSender = new DetectionSender();

        vehicleTracker.setTrackingListener(new VehicleTracker.TrackingListener() {
            @Override
            public void onNewVehicle(TrackedVehicle vehicle) {
                Log.d(TAG, "Ново превозно средство: " + vehicle.trackerId);
            }

            @Override
            public void onVehicleLost(TrackedVehicle vehicle) {
                Log.d(TAG, "Изгубено превозно средство: " + vehicle.trackerId);
            }
        });

        // Заявяваме runtime разрешения при нужда
        if (allPermissionsGranted()) {
            initializeAndStart();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Препрочитаме настройките при завръщане от SettingsActivity
        if (detectionSender != null) {
            SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
            String url = prefs.getString(SettingsActivity.KEY_SERVER_URL, SettingsActivity.DEFAULT_SERVER_URL);
            detectionSender.setServerBaseUrl(url);

            if (vehicleDetector != null) {
                float confidence = prefs.getFloat(SettingsActivity.KEY_CONFIDENCE, SettingsActivity.DEFAULT_CONFIDENCE);
                vehicleDetector.setConfidenceThreshold(confidence);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraManager   != null) cameraManager.shutdown();
        if (vehicleDetector != null) vehicleDetector.close();
        if (locationModule  != null) locationModule.stopLocationUpdates();
        if (detectionSender != null) detectionSender.shutdown();
        inferenceExecutor.shutdown();
    }

    // ── Инициализация ─────────────────────────────────────────────────────────

    /**
     * Извиква се след получаване на разрешения.
     * Зарежда настройките, инициализира всички компоненти и стартира системата.
     */
    private void initializeAndStart() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        float confidence = prefs.getFloat(SettingsActivity.KEY_CONFIDENCE, SettingsActivity.DEFAULT_CONFIDENCE);
        long  gpsInterval = prefs.getLong(SettingsActivity.KEY_GPS_INTERVAL, SettingsActivity.DEFAULT_GPS_INTERVAL);
        String serverUrl  = prefs.getString(SettingsActivity.KEY_SERVER_URL, SettingsActivity.DEFAULT_SERVER_URL);

        detectionSender.setServerBaseUrl(serverUrl);

        // TFLite детектор — зарежда model.tflite от assets
        try {
            vehicleDetector = new VehicleDetector(this, confidence);
        } catch (Exception e) {
            Log.e(TAG, "Неуспешно зареждане на TFLite модела: " + e.getMessage());
            Toast.makeText(this,
                    "Моделът не е намерен. Постави model.tflite в assets/",
                    Toast.LENGTH_LONG).show();
            // Приложението продължава без детекция
        }

        // GPS модул
        locationModule = new LocationModule(this, gpsInterval);
        locationModule.setLocationListener((lat, lon, speed, bearing) -> {
            currentLat        = lat;
            currentLon        = lon;
            currentSpeedKmh   = speed;
            currentBearingDeg = bearing;
        });
        locationModule.startLocationUpdates();

        // CameraX — стартира preview и frame analysis
        cameraManager = new CameraManager(this, this, previewView, this::onNewFrame);
        cameraManager.startCamera();

        updateConnectionStatus(true);
    }

    // ── Обработка на кадри ────────────────────────────────────────────────────

    /**
     * Callback от CameraManager — извиква се за всеки нов кадър.
     * Изпълнява се на camera executor thread (не е main thread).
     *
     * @param bitmap      Текущият кадър от камерата
     * @param timestampMs Unix timestamp в милисекунди
     */
    private void onNewFrame(Bitmap bitmap, long timestampMs) {
        // Буферираме кадъра за евентуално изпращане
        detectionSender.bufferFrame(bitmap);

        if (vehicleDetector == null) return;

        // ML inference на dedicated background thread
        inferenceExecutor.execute(() -> {
            List<DetectionResult> detections = vehicleDetector.detect(bitmap);
            List<TrackedVehicle> trackedVehicles = vehicleTracker.update(detections);

            // Обновяваме overlay на main thread
            mainHandler.post(() -> overlayView.setVehicles(trackedVehicles));

            // Проверяваме кои обекти трябва да бъдат изпратени
            for (TrackedVehicle vehicle : trackedVehicles) {
                if (vehicleTracker.shouldSendToServer(vehicle)) {
                    vehicleTracker.markAsSent(vehicle);
                    sendVehicleDetection(vehicle, timestampMs);
                }
            }
        });
    }

    // ── Изпращане към сървъра ─────────────────────────────────────────────────

    /**
     * Изпраща информация за ново или повторно засечено превозно средство.
     * Добавя запис в UI лога и уведомява при успех/грешка.
     *
     * @param vehicle     Засеченото превозно средство
     * @param timestampMs Момент на засичане (Unix ms)
     */
    private void sendVehicleDetection(TrackedVehicle vehicle, long timestampMs) {
        String trackerId    = vehicle.trackerId;
        String isoTimestamp = isoFormat.format(new Date(timestampMs));
        String timeLabel    = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                 .format(new Date(timestampMs));

        // Добавяме нов ред в лога с "Изпраща..." статус
        mainHandler.post(() ->
                logAdapter.addItem(new DetectionLogItem(trackerId, timeLabel, "Изпраща...")));

        // Подготвяме JSON метаданни
        DetectionMetadata metadata = new DetectionMetadata(
                trackerId,
                isoTimestamp,
                currentLat,
                currentLon,
                currentSpeedKmh,
                currentBearingDeg
        );

        detectionSender.sendDetection(metadata, new DetectionSender.SendCallback() {
            @Override
            public void onSuccess(String detectionId) {
                mainHandler.post(() -> {
                    logAdapter.updateItemStatus(trackerId, "Успешно");
                    updateConnectionStatus(true);
                });
            }

            @Override
            public void onError(String errorMessage) {
                mainHandler.post(() -> {
                    logAdapter.updateItemStatus(trackerId, "Грешка");
                    updateConnectionStatus(false);
                    Log.w(TAG, "Грешка при изпращане [" + trackerId + "]: " + errorMessage);
                });
            }
        });
    }

    // ── Помощни методи ────────────────────────────────────────────────────────

    /** Обновява индикатора за свързаност в горната лента. */
    private void updateConnectionStatus(boolean connected) {
        tvConnectionStatus.setText(connected ? "● Свързан" : "● Несвързан");
        tvConnectionStatus.setTextColor(connected ? 0xFF4CAF50 : 0xFFF44336);
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                            @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                initializeAndStart();
            } else {
                Toast.makeText(this,
                        "Необходими са разрешения за камера и локация.",
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
}
