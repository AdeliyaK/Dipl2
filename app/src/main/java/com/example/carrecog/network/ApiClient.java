package com.example.carrecog.network;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.util.concurrent.TimeUnit;

/**
 * Синглтон фабрика за Retrofit/OkHttp клиента.
 * Пресъздава клиента при промяна на базовия URL (настройки).
 */
public class ApiClient {

    private static final int CONNECT_TIMEOUT_SEC = 10;
    private static final int READ_TIMEOUT_SEC    = 30;
    private static final int WRITE_TIMEOUT_SEC   = 60; // за upload на снимки

    private static Retrofit retrofit;
    private static String currentBaseUrl;

    /**
     * Връща ApiService инстанция. Пресъздава Retrofit при промяна на URL.
     */
    public static ApiService getService(String baseUrl) {
        if (retrofit == null || !baseUrl.equals(currentBaseUrl)) {
            currentBaseUrl = baseUrl;
            retrofit = buildRetrofit(baseUrl);
        }
        return retrofit.create(ApiService.class);
    }

    private static Retrofit buildRetrofit(String baseUrl) {
        // Logging interceptor — показва HTTP заявки/отговори в LogCat (само в debug)
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build();

        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
    }

    /** Нулира инстанцията при промяна на сървърния URL от настройките. */
    public static void reset() {
        retrofit = null;
        currentBaseUrl = null;
    }
}
