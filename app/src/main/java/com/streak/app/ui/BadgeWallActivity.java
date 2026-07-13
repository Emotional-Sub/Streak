package com.streak.app.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.streak.app.R;
import com.streak.app.databinding.ActivityBadgeWallBinding;
import com.streak.app.databinding.ItemBadgeBinding;
import com.streak.app.model.Badge;
import com.streak.app.storage.AppRepository;
import com.streak.app.util.BadgeUtils;

import java.util.List;

public class BadgeWallActivity extends AppCompatActivity {

    private static final int COLUMNS = 4;

    private ActivityBadgeWallBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityBadgeWallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbarBadgeWall.setNavigationOnClickListener(v -> finish());

        AppRepository repository = new AppRepository(this);
        List<Badge> badges = BadgeUtils.evaluate(repository.readHabits());
        int unlocked = BadgeUtils.unlockedCount(badges);
        binding.tvBadgeWallSummary.setText(getString(R.string.badge_wall_summary, unlocked, badges.size()));

        renderGrid(badges);
    }

    private void renderGrid(List<Badge> badges) {
        LinearLayout grid = binding.layoutBadgeGrid;
        grid.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < badges.size(); i++) {
            if (i % COLUMNS == 0) {
                row = newRow();
                grid.addView(row);
            }
            row.addView(buildCell(badges.get(i)));
        }
        // 补齐最后一行空位，保持列对齐
        if (row != null) {
            int remainder = badges.size() % COLUMNS;
            if (remainder != 0) {
                for (int i = 0; i < COLUMNS - remainder; i++) {
                    row.addView(spacer());
                }
            }
        }
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private View buildCell(Badge badge) {
        ItemBadgeBinding cell = ItemBadgeBinding.inflate(getLayoutInflater(), binding.layoutBadgeGrid, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        int vertical = (int) (10 * getResources().getDisplayMetrics().density);
        lp.topMargin = vertical;
        cell.getRoot().setLayoutParams(lp);

        cell.tvBadgeIcon.setText(badge.getEmoji());
        cell.tvBadgeName.setText(badge.getTitle());
        if (badge.isUnlocked()) {
            cell.tvBadgeIcon.setBackgroundResource(R.drawable.bg_badge_unlocked);
            cell.tvBadgeIcon.setAlpha(1f);
            cell.tvBadgeName.setTextColor(ContextCompat.getColor(this, R.color.streak_ink));
        } else {
            cell.tvBadgeIcon.setBackgroundResource(R.drawable.bg_badge_locked);
            cell.tvBadgeIcon.setAlpha(0.45f);
            cell.tvBadgeName.setTextColor(ContextCompat.getColor(this, R.color.streak_muted));
        }
        cell.getRoot().setOnClickListener(v ->
                android.widget.Toast.makeText(this,
                        badge.getTitle() + "：" + badge.getDescription()
                                + getString(badge.isUnlocked() ? R.string.badge_lit_suffix : R.string.badge_unlit_suffix),
                        android.widget.Toast.LENGTH_SHORT).show());
        return cell.getRoot();
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }
}
