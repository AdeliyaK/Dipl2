package com.example.carrecog;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Екран за конфигуриране на приложението.
 * Настройките се запазват в SharedPreferences и се зареждат при всяко стартиране.
 *
 * Налични настройки:
 *   - Праг на детекция (confidence threshold) — от 0.10 до 1.00
 *   - Честота на GPS обновяване (ms)
 *   - URL на сървъра
 */
public class SettingsActivity extends AppCompatActivity {

    public static final String PREFS_NAME       = "carrecog_prefs";
    public static final String KEY_CONFIDENCE   = "confidence_threshold";
    public static final String KEY_GPS_INTERVAL = "gps_interval_ms";
    public static final String KEY_SERVER_URL   = "server_url";

    public static final float  DEFAULT_CONFIDENCE   = 0.50f;
    public static final long   DEFAULT_GPS_INTERVAL = 2000L;
    public static final String DEFAULT_SERVER_URL   = "http://192.168.1.100:8080/";

    private SeekBar  seekBarConfidence;
    private TextView tvConfidenceValue;
    private EditText etGpsInterval;
    private EditText etServerUrl;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_settings);
        }

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        seekBarConfidence = findViewById(R.id.seekbar_confidence);
        tvConfidenceValue = findViewById(R.id.tv_confidence_value);
        etGpsInterval     = findViewById(R.id.et_gps_interval);
        etServerUrl       = findViewById(R.id.et_server_url);
        Button btnSave    = findViewById(R.id.btn_save_settings);

        loadSettings();

        seekBarConfidence.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // SeekBar е в диапазон 10–100 → confidence 0.10–1.00
                float value = progress / 100f;
                tvConfidenceValue.setText(String.format("%.2f", value));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnSave.setOnClickListener(v -> saveSettings());
    }

    private void loadSettings() {
        float confidence = prefs.getFloat(KEY_CONFIDENCE, DEFAULT_CONFIDENCE);
        long gpsInterval  = prefs.getLong(KEY_GPS_INTERVAL, DEFAULT_GPS_INTERVAL);
        String serverUrl  = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);

        seekBarConfidence.setProgress((int) (confidence * 100));
        tvConfidenceValue.setText(String.format("%.2f", confidence));
        etGpsInterval.setText(String.valueOf(gpsInterval));
        etServerUrl.setText(serverUrl);
    }

    private void saveSettings() {
        float confidence = seekBarConfidence.getProgress() / 100f;

        long gpsInterval;
        try {
            gpsInterval = Long.parseLong(etGpsInterval.getText().toString().trim());
            if (gpsInterval < 500) gpsInterval = 500; // минимум 500 ms
        } catch (NumberFormatException e) {
            gpsInterval = DEFAULT_GPS_INTERVAL;
        }

        String serverUrl = etServerUrl.getText().toString().trim();
        if (serverUrl.isEmpty()) serverUrl = DEFAULT_SERVER_URL;
        if (!serverUrl.endsWith("/")) serverUrl += "/";

        prefs.edit()
                .putFloat(KEY_CONFIDENCE, confidence)
                .putLong(KEY_GPS_INTERVAL, gpsInterval)
                .putString(KEY_SERVER_URL, serverUrl)
                .apply();

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}
