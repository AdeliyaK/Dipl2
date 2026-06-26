package com.example.carrecog.location;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * Модул за получаване на GPS координати, скорост и посока на устройството.
 *
 * Използва FusedLocationProviderClient за оптимален баланс между
 * точност и консумация на батерия.
 * Скоростта и посоката се извличат директно от Location обекта когато
 * са налични, или се изчисляват от последователни GPS точки.
 */
public class LocationModule {

    private static final String TAG = "LocationModule";

    private final FusedLocationProviderClient fusedClient;
    private LocationCallback locationCallback;
    private Location lastLocation;
    private long intervalMs;

    public interface LocationListener {
        /**
         * Извиква се при всяко ново GPS обновление.
         *
         * @param lat       Географска ширина (°)
         * @param lon       Географска дължина (°)
         * @param speedKmh  Скорост на устройството (km/h)
         * @param bearingDeg Посока на движение (0–360°, 0=Север)
         */
        void onLocationUpdate(double lat, double lon, float speedKmh, float bearingDeg);
    }

    private LocationListener listener;

    public LocationModule(Context context, long intervalMs) {
        this.fusedClient = LocationServices.getFusedLocationProviderClient(context);
        this.intervalMs = intervalMs;
    }

    public void setLocationListener(LocationListener listener) {
        this.listener = listener;
    }

    /**
     * Стартира получаването на GPS обновления.
     * Изисква разрешения ACCESS_FINE_LOCATION и ACCESS_COARSE_LOCATION.
     */
    @SuppressLint("MissingPermission")
    public void startLocationUpdates() {
        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs / 2)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) return;
                for (Location loc : result.getLocations()) {
                    processLocation(loc);
                }
            }
        };

        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
        Log.i(TAG, "GPS обновленията стартирани. Интервал: " + intervalMs + " ms");
    }

    /**
     * Изчислява скорост и посока от текущата GPS точка.
     * Посоката се изчислява от предишната точка ако устройството не я предоставя директно.
     */
    private void processLocation(Location location) {
        // Скоростта е директно от GPS сензора (m/s → km/h)
        float speedKmh = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;

        // Посоката: директно от GPS или изчислена от предишна точка
        float bearingDeg = 0f;
        if (location.hasBearing()) {
            bearingDeg = location.getBearing();
        } else if (lastLocation != null) {
            bearingDeg = lastLocation.bearingTo(location);
        }

        lastLocation = location;

        if (listener != null) {
            listener.onLocationUpdate(
                    location.getLatitude(),
                    location.getLongitude(),
                    speedKmh,
                    bearingDeg);
        }
    }

    /** Спира GPS обновленията за пестене на батерия. */
    public void stopLocationUpdates() {
        if (locationCallback != null) {
            fusedClient.removeLocationUpdates(locationCallback);
            Log.i(TAG, "GPS обновленията спрени.");
        }
    }

    /** Обновява интервала (например при промяна в настройките). */
    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public Location getLastLocation() {
        return lastLocation;
    }
}
