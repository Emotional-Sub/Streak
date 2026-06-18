package com.streak.app.ui;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.streak.app.R;
import com.streak.app.databinding.ItemHabitBinding;
import com.streak.app.model.HabitItem;
import com.streak.app.util.HabitUtils;

import java.util.ArrayList;
import java.util.List;

public class HabitAdapter extends RecyclerView.Adapter<HabitAdapter.HabitViewHolder> {
    public interface Callback {
        void onToggleComplete(HabitItem item);

        void onEdit(HabitItem item);

        void onDelete(HabitItem item);
    }

    private final Callback callback;
    private final List<HabitItem> items = new ArrayList<>();
    private String today = HabitUtils.today();

    public HabitAdapter(Callback callback) {
        this.callback = callback;
    }

    public void submitList(List<HabitItem> source, String today) {
        this.items.clear();
        if (source != null) {
            this.items.addAll(source);
        }
        this.today = today;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public HabitViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemHabitBinding binding = ItemHabitBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        );
        return new HabitViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull HabitViewHolder holder, int position) {
        holder.bind(items.get(position), today, callback);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class HabitViewHolder extends RecyclerView.ViewHolder {
        private final ItemHabitBinding binding;

        HabitViewHolder(ItemHabitBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(HabitItem item, String today, Callback callback) {
            boolean completedToday = item.getCompletedDates() != null && item.getCompletedDates().contains(today);
            int totalCheckIns = item.getCompletedDates() == null ? 0 : item.getCompletedDates().size();
            int streak = HabitUtils.currentStreak(item.getCompletedDates());

            binding.tvHabitTitle.setText(item.getTitle());
            binding.tvHabitCreatedAt.setText("创建时间：" + item.getCreatedAt());
            binding.tvHabitContent.setText(item.getContent());
            binding.tvHabitMeta.setText(
                    item.getCategory() + " · 提醒 " + item.getReminderTime()
                            + " · 已打卡 " + totalCheckIns + " 次"
                            + " · 连续 " + streak + " 天"
            );

            if (item.getTags() != null && !item.getTags().isEmpty()) {
                StringBuilder tagsText = new StringBuilder();
                for (String tag : item.getTags()) {
                    tagsText.append("#").append(tag).append(" ");
                }
                binding.tvHabitTags.setText(tagsText.toString().trim());
                binding.tvHabitTags.setVisibility(View.VISIBLE);
            } else {
                binding.tvHabitTags.setVisibility(View.GONE);
            }

            if (item.getImageUri() != null && !item.getImageUri().trim().isEmpty()) {
                binding.ivHabitImage.setImageURI(Uri.parse(item.getImageUri()));
                binding.ivHabitImage.setVisibility(View.VISIBLE);
            } else {
                binding.ivHabitImage.setVisibility(View.GONE);
                binding.ivHabitImage.setImageDrawable(null);
            }

            binding.viewStatusDot.setBackgroundResource(
                    completedToday ? R.drawable.bg_status_done : R.drawable.bg_status_pending
            );
            binding.btnHabitComplete.setText(completedToday ? "已打卡" : "打卡");
            binding.btnHabitComplete.setOnClickListener(v -> callback.onToggleComplete(item));
            binding.btnHabitEdit.setOnClickListener(v -> callback.onEdit(item));
            binding.btnHabitDelete.setOnClickListener(v -> callback.onDelete(item));
        }
    }
}
