package com.example.carrecog.network;

import android.graphics.Bitmap;
import android.util.Log;

import com.example.carrecog.camera.CameraManager;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Отговаря за асинхронното изпращане на засичания към сървъра.
 *
 * Поддържа циркулярен буфер с последните BUFFER_SIZE кадри.
 * При засичане на ново превозно средство изпраща последните
 * MAX_FRAMES_PER_REQUEST JPEG кадри заедно с JSON метаданни.
 *
 * Мрежовата заявка е асинхронна (ExecutorService + Retrofit Callback),
 * така че никога не блокира UI/camera thread-овете.
 */
public class DetectionSender {

    private static final String TAG = "DetectionSender";

    private static final int BUFFER_SIZE            = 8;    // брой кадри в буфера
    private static final int MAX_FRAMES_PER_REQUEST = 5;    // кадри на изпращане
    private static final int JPEG_QUALITY           = 80;   // компресия (0-100)

    private static final MediaType MEDIA_TYPE_JPEG =
            MediaType.parse("image/jpeg");
    private static final MediaType MEDIA_TYPE_JSON =
            MediaType.parse("application/json; charset=utf-8");

    // Thread-safe циркулярен буфер с последните кадри
    private final ConcurrentLinkedQueue<Bitmap> frameBuffer = new ConcurrentLinkedQueue<>();

    private final ExecutorService networkExecutor = Executors.newFixedThreadPool(2);
    private final Gson gson = new Gson();

    private String serverBaseUrl;

    public DetectionSender() {
        this.serverBaseUrl = "http://192.168.1.100:8080/";
    }

    /**
     * Добавя кадър в буфера. Извиква се при всеки нов кадър от камерата.
     * Старите кадри автоматично се изместват при пълен буфер.
     */
    public void bufferFrame(Bitmap bitmap) {
        frameBuffer.offer(bitmap);
        // Изхвърляме най-стария кадър при препълване
        while (frameBuffer.size() > BUFFER_SIZE) {
            frameBuffer.poll();
        }
    }

    /**
     * Изпраща засичане към сървъра асинхронно.
     * Взима последните MAX_FRAMES_PER_REQUEST кадри от буфера.
     *
     * @param metadata  JSON метаданни (координати, скорост, tracker ID)
     * @param callback  Резултат от изпращането (success/error)
     */
    public void sendDetection(DetectionMetadata metadata, SendCallback callback) {
        // Снимаме текущото съдържание на буфера
        List<Bitmap> allFrames = new ArrayList<>(frameBuffer);
        int start = Math.max(0, allFrames.size() - MAX_FRAMES_PER_REQUEST);
        List<Bitmap> framesToSend = allFrames.subList(start, allFrames.size());

        if (framesToSend.isEmpty()) {
            Log.w(TAG, "Буферът е празен — няма кадри за изпращане.");
            if (callback != null) callback.onError("Липсват кадри в буфера");
            return;
        }

        // Делегираме мрежовата операция на background thread
        networkExecutor.execute(() -> performSend(framesToSend, metadata, callback));
    }

    /**
     * Изпълнява действителната HTTP multipart заявка.
     * Работи на networkExecutor thread — не блокира UI.
     */
    private void performSend(List<Bitmap> frames,
                              DetectionMetadata metadata,
                              SendCallback callback) {
        try {
            // Подготвяме multipart части — по един JPEG на кадър
            List<MultipartBody.Part> frameParts = new ArrayList<>();
            for (int i = 0; i < frames.size(); i++) {
                byte[] jpegBytes = CameraManager.bitmapToJpegBytes(frames.get(i), JPEG_QUALITY);
                RequestBody body = RequestBody.create(MEDIA_TYPE_JPEG, jpegBytes);
                frameParts.add(MultipartBody.Part.createFormData(
                        "frames[]", "frame_" + i + ".jpg", body));
            }

            // Сериализираме метаданните като JSON
            String metadataJson = gson.toJson(metadata);
            RequestBody metadataBody = RequestBody.create(MEDIA_TYPE_JSON, metadataJson);

            Log.d(TAG, "Изпращане: " + frames.size() + " кадъра, tracker=" + metadata.trackerId);

            // Retrofit изпраща заявката асинхронно и извиква callback-а
            ApiClient.getService(serverBaseUrl)
                     .sendDetection(frameParts, metadataBody)
                     .enqueue(new Callback<ServerResponse>() {

                @Override
                public void onResponse(Call<ServerResponse> call,
                                       Response<ServerResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        String detId = response.body().detectionId;
                        Log.i(TAG, "Успешно изпратено. detection_id=" + detId);
                        if (callback != null) callback.onSuccess(detId);
                    } else {
                        String err = "HTTP " + response.code();
                        Log.w(TAG, "Сървърна грешка: " + err);
                        if (callback != null) callback.onError(err);
                    }
                }

                @Override
                public void onFailure(Call<ServerResponse> call, Throwable t) {
                    Log.e(TAG, "Мрежова грешка: " + t.getMessage());
                    if (callback != null) callback.onError(t.getMessage());
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Изключение при изпращане: " + e.getMessage());
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    public void setServerBaseUrl(String url) {
        this.serverBaseUrl = url;
        ApiClient.reset();
    }

    public void shutdown() {
        networkExecutor.shutdown();
    }

    /** Callback интерфейс за резултата от изпращането. */
    public interface SendCallback {
        void onSuccess(String detectionId);
        void onError(String errorMessage);
    }
}
