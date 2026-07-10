package com.streak.app.ui;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.streak.app.R;
import com.streak.app.databinding.ItemHabitBinding;
import com.streak.app.databinding.ItemHabitSectionBinding;
import com.streak.app.model.HabitItem;
import com.streak.app.util.HabitUtils;

import java.util.ArrayList;
import java.util.List;

public class HabitAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Callback {
        void onToggleComplete(HabitItem item);

        void onEdit(HabitItem item);

        void onShare(HabitItem item);

        void onDelete(HabitItem item);
    }

    private static final int TYPE_SECTION = 0;
    private static final int TYPE_HABIT = 1;

    /** 列表行：分组标题（仅 title 有值）或习惯卡（仅 habit 有值）。 */
    private static class Row {
        final String sectionTitle;
        final HabitItem habit;

        Row(String sectionTitle, HabitItem habit) {
            this.sectionTitle = sectionTitle;
            this.habit = habit;
        }
    }

    private final Callback callback;
    private final List<Row> rows = new ArrayList<>();
    private String today = HabitUtils.today();

    public HabitAdapter(Callback callback) {
        this.callback = callback;
    }

    /**
     * 重新组装列表：未打卡的习惯排在「未打卡」分组、已打卡的排在「已打卡」分组（沉到底部）。
     * 任一分组为空则不显示其标题。
     */
    public void submitList(List<HabitItem> source, String today) {
        this.today = today;
        rows.clear();
        if (source != null) {
            List<HabitItem> pending = new ArrayList<>();
            List<HabitItem> done = new ArrayList<>();
            for (HabitItem item : source) {
                boolean completedToday = item.getCompletedDates() != null
                        && item.getCompletedDates().contains(today);
                if (completedToday) {
                    done.add(item);
                } else {
                    pending.add(item);
                }
            }
            if (!pending.isEmpty()) {
                rows.add(new Row("未打卡 · " + pending.size(), null));
                for (HabitItem item : pending) {
                    rows.add(new Row(null, item));
                }
            }
            if (!done.isEmpty()) {
                rows.add(new Row("已打卡 · " + done.size(), null));
                for (HabitItem item : done) {
                    rows.add(new Row(null, item));
                }
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).habit == null ? TYPE_SECTION : TYPE_HABIT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_SECTION) {
            return new SectionViewHolder(ItemHabitSectionBinding.inflate(inflater, parent, false));
        }
        return new HabitViewHolder(ItemHabitBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof SectionViewHolder) {
            ((SectionViewHolder) holder).bind(row.sectionTitle);
        } else {
            ((HabitViewHolder) holder).bind(row.habit, today, callback);
        }
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class SectionViewHolder extends RecyclerView.ViewHolder {
        private final ItemHabitSectionBinding binding;

        SectionViewHolder(ItemHabitSectionBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(String title) {
            binding.tvHabitSectionTitle.setText(title);
        }
    }

    static class HabitViewHolder extends RecyclerView.ViewHolder {
        private final ItemHabitBinding binding;

        HabitViewHolder(ItemHabitBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(HabitItem item, String today, Callback callback) {
            boolean completedToday = item.getCompletedDates() != null && item.getCompletedDates().contains(today);
            int totalCheckIns = HabitUtils.uniqueCheckIns(item);
            int streak = HabitUtils.currentStreak(item.getCompletedDates());

            binding.tvHabitTitle.setText(item.getTitle());
            binding.tvHabitCreatedAt.setText("创建时间：" + item.getCreatedAt());
            binding.tvHabitContent.setText(item.getContent());
            String goalText = item.isWeeklyGoal()
                    ? "每周 " + item.getWeeklyTarget() + " 次" : "每天";
            binding.tvHabitMeta.setText(
                    item.getCategory() + " · " + goalText + " · 提醒 " + item.getReminderTime()
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
                com.streak.app.util.ImageLoader.load(binding.ivHabitImage, item.getImageUri(), 600);
                binding.ivHabitImage.setVisibility(View.VISIBLE);
            } else {
                binding.ivHabitImage.setVisibility(View.GONE);
                binding.ivHabitImage.setImageDrawable(null);
            }

            binding.viewStatusDot.setBackgroundResource(
                    completedToday ? R.drawable.bg_status_done : R.drawable.bg_status_pending
            );
            binding.btnHabitComplete.setImageResource(
                    completedToday ? R.drawable.ic_check_done : R.drawable.ic_check_pending
            );
            binding.btnHabitComplete.setContentDescription(completedToday ? "已打卡" : "打卡");
            binding.btnHabitComplete.setOnClickListener(v -> callback.onToggleComplete(item));
            binding.btnHabitEdit.setOnClickListener(v -> callback.onEdit(item));
            binding.btnHabitShare.setOnClickListener(v -> callback.onShare(item));
            binding.btnHabitDelete.setOnClickListener(v -> callback.onDelete(item));
        }
    }
}
