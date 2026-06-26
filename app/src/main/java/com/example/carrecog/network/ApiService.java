package com.example.carrecog.network;

import java.util.List;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

/**
 * Retrofit интерфейс за комуникация с backend сървъра.
 *
 * API контракт: POST /api/v1/detections
 * Content-Type: multipart/form-data
 *   - frames[]: JPEG файлове (3-5 бр.)
 *   - metadata: JSON с координати, скорост, посока и tracker ID
 */
public interface ApiService {

    @Multipart
    @POST("api/v1/detections")
    Call<ServerResponse> sendDetection(
            @Part List<MultipartBody.Part> frames,
            @Part("metadata") RequestBody metadata
    );
}
