package com.example.carrecog.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Управлява CameraX pipeline за заснемане и анализ на кадри в реално време.
 *
 * Конфигурира два CameraX use cases:
 *   1. Preview — показва live feed в PreviewView
 *   2. ImageAnalysis — доставя кадри за ML inference
 *
 * Стратегията STRATEGY_KEEP_ONLY_LATEST гарантира, че при забавяне на
 * анализа се обработва само последният кадър (не се натрупва опашка).
 */
public class CameraManager {

    private static final String TAG = "CameraManager";
    private static final int TARGET_WIDTH  = 640;
    private static final int TARGET_HEIGHT = 480;

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final PreviewView previewView;
    private final FrameCallback frameCallback;

    // Dedicated thread за camera analysis — не блокира главния thread
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();

    private ProcessCameraProvider cameraProvider;

    /**
     * Callback, извикван за всеки нов кадър от камерата.
     * Извикването е от camera executor thread.
     */
    public interface FrameCallback {
        void onFrame(Bitmap bitmap, long timestampMs);
    }

    public CameraManager(@NonNull Context context,
                          @NonNull LifecycleOwner lifecycleOwner,
                          @NonNull PreviewView previewView,
                          @NonNull FrameCallback frameCallback) {
        this.context = context;
        this.lifecycleOwner = lifecycleOwner;
        this.previewView = previewView;
        this.frameCallback = frameCallback;
    }

    /** Стартира CameraX — асинхронно получава ProcessCameraProvider и свързва use cases. */
    public void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(context);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Неуспешно стартиране на камерата: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void bindUseCases() {
        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        // Preview: визуализира live feed за потребителя
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // ImageAnalysis: доставя кадри за ML inference
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(TARGET_WIDTH, TARGET_HEIGHT))
                // Само последният кадър се анализира при забавяне
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, this::processFrame);

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis);
            Log.i(TAG, "CameraX use cases свързани успешно.");
        } catch (Exception e) {
            Log.e(TAG, "Грешка при свързване на use cases: " + e.getMessage());
        }
    }

    /**
     * Обработва всеки кадър: конвертира ImageProxy в Bitmap и уведомява callback-а.
     * Методът се изпълнява на camera executor thread.
     */
    private void processFrame(@NonNull ImageProxy imageProxy) {
        long timestampMs = System.currentTimeMillis();
        try {
            // CameraX 1.3+ предоставя директна конвертация в Bitmap
            Bitmap bitmap = imageProxy.toBitmap();

            // Завъртаме изображението спрямо ориентацията на устройството
            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                bitmap = Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }

            frameCallback.onFrame(bitmap, timestampMs);
        } finally {
            // Задължително затваряме proxy-то, иначе pipeline-ът спира
            imageProxy.close();
        }
    }

    /**
     * Конвертира Bitmap в JPEG байтове за включване в multipart заявка.
     * @param quality JPEG качество (0–100); препоръчва се 75–85 за баланс размер/качество
     */
    public static byte[] bitmapToJpegBytes(@NonNull Bitmap bitmap, int quality) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }

    /** Освобождава ресурсите — извиква се от onDestroy на Activity-то. */
    public void shutdown() {
        cameraExecutor.shutdown();
    }
}
