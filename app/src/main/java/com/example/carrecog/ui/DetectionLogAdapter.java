package com.example.carrecog.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.carrecog.R;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView адаптер за показване на последните засичания.
 * Нов елемент се вмъква в началото; старите се изместват надолу.
 */
public class DetectionLogAdapter extends RecyclerView.Adapter<DetectionLogAdapter.ViewHolder> {

    private static final int MAX_ITEMS = 50;

    private static final int COLOR_SUCCESS = 0xFF4CAF50; // зелено
    private static final int COLOR_ERROR   = 0xFFF44336; // червено
    private static final int COLOR_PENDING = 0xFFFF9800; // оранжево

    private final List<DetectionLogItem> items = new ArrayList<>();

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_detection_log, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DetectionLogItem item = items.get(position);
        holder.tvTrackerId.setText(item.trackerId);
        holder.tvTime.setText(item.time);
        holder.tvStatus.setText(item.status);
        holder.tvStatus.setTextColor(statusColor(item.status));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** Добавя нов елемент в началото на списъка. */
    public void addItem(@NonNull DetectionLogItem item) {
        items.add(0, item);
        if (items.size() > MAX_ITEMS) {
            items.remove(items.size() - 1);
        }
        notifyItemInserted(0);
    }

    /** Обновява статуса на съществуващ елемент (по tracker ID). */
    public void updateItemStatus(@NonNull String trackerId, @NonNull String newStatus) {
        for (int i = 0; i < items.size(); i++) {
            if (trackerId.equals(items.get(i).trackerId)) {
                items.get(i).status = newStatus;
                notifyItemChanged(i);
                return;
            }
        }
    }

    private int statusColor(String status) {
        switch (status) {
            case "Успешно": return COLOR_SUCCESS;
            case "Грешка":  return COLOR_ERROR;
            default:        return COLOR_PENDING;
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvTrackerId;
        final TextView tvTime;
        final TextView tvStatus;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTrackerId = itemView.findViewById(R.id.tv_tracker_id);
            tvTime      = itemView.findViewById(R.id.tv_time);
            tvStatus    = itemView.findViewById(R.id.tv_status);
        }
    }
}
