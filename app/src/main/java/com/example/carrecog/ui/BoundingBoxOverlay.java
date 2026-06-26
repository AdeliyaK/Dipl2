package com.example.carrecog.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.example.carrecog.tracking.TrackedVehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom View, рисуван върху camera preview, показва bounding boxes
 * на засечените превозни средства.
 *
 * Координатите на детекциите са нормализирани [0,1] и се мащабират
 * динамично към реалните пикселни размери на View-то при всяко onDraw().
 * Актуализира се само при нов кадър с детекции (invalidate() чрез setVehicles()).
 */
public class BoundingBoxOverlay extends View {

    private static final int   BOX_COLOR       = Color.RED;
    private static final float BOX_STROKE_WIDTH = 4f;
    private static final float TEXT_SIZE        = 34f;
    private static final float LABEL_PADDING    = 6f;

    private final Paint boxPaint;
    private final Paint textPaint;
    private final Paint labelBgPaint;

    private List<TrackedVehicle> vehicles = new ArrayList<>();

    public BoundingBoxOverlay(Context context) {
        this(context, null);
    }

    public BoundingBoxOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);

        boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setColor(BOX_COLOR);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(BOX_STROKE_WIDTH);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(TEXT_SIZE);

        labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelBgPaint.setColor(BOX_COLOR);
        labelBgPaint.setStyle(Paint.Style.FILL);

        // View-то е прозрачно — показва само bounding boxes
        setBackgroundColor(Color.TRANSPARENT);
    }

    /**
     * Обновява списъка с активни превозни средства и прерисува overlay-а.
     * Трябва да се извиква от UI thread.
     */
    public void setVehicles(List<TrackedVehicle> vehicles) {
        this.vehicles = new ArrayList<>(vehicles);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        for (TrackedVehicle vehicle : vehicles) {
            RectF norm = vehicle.boundingBox;

            // Мащабираме нормализираните координати към пикселни
            float left   = norm.left   * w;
            float top    = norm.top    * h;
            float right  = norm.right  * w;
            float bottom = norm.bottom * h;

            // Рисуваме bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint);

            // Рисуваме label с tracker ID над кутията
            String label = vehicle.trackerId;
            float textWidth = textPaint.measureText(label);
            float labelTop = Math.max(0, top - TEXT_SIZE - LABEL_PADDING * 2);

            canvas.drawRect(left, labelTop,
                    left + textWidth + LABEL_PADDING * 2,
                    top, labelBgPaint);
            canvas.drawText(label,
                    left + LABEL_PADDING,
                    labelTop + TEXT_SIZE, textPaint);
        }
    }

    public void clear() {
        vehicles.clear();
        invalidate();
    }
}
