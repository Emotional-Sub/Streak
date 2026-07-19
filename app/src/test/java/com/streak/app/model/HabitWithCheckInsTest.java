package com.streak.app.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** HabitWithCheckIns 的深度快照和不可变集合契约测试。 */
public class HabitWithCheckInsTest {

    @Test
    public void snapshotIsDetachedFromSourceAndReturnedCopies() {
        HabitItem source = new HabitItem(42L, "阅读", "内容", "20:00",
                "2026-01-01 10:00", null, "学习",
                new ArrayList<>(Arrays.asList("英语")),
                new ArrayList<>(), true);
        source.setOwnerUsername("alice");

        CheckInRecord sourceRecord = new CheckInRecord();
        sourceRecord.setHabitId(42L);
        sourceRecord.setDate("2026-01-01");
        sourceRecord.setNote("专注");
        sourceRecord.setMood(4);
        List<CheckInRecord> sourceRecords = new ArrayList<>(Arrays.asList(sourceRecord));

        HabitWithCheckIns view = new HabitWithCheckIns(source, sourceRecords);

        source.setId(99L);
        source.getTags().add("被修改");
        sourceRecord.setDate("2026-01-02");
        sourceRecords.clear();

        assertEquals(42L, view.habitId());
        assertEquals("2026-01-01", view.dateList().get(0));
        assertEquals("专注", view.note("2026-01-01"));

        HabitItem returnedHabit = view.habit();
        returnedHabit.setId(100L);
        returnedHabit.getTags().clear();
        assertEquals(42L, view.habitId());
        assertEquals(1, view.habit().getTags().size());

        List<CheckInRecord> returnedRecords = view.records();
        returnedRecords.get(0).setDate("2026-01-03");
        assertEquals("2026-01-01", view.dateList().get(0));
    }

    @Test
    public void derivedCollectionsAreUnmodifiable() {
        HabitItem habit = new HabitItem();
        habit.setId(7L);
        CheckInRecord record = new CheckInRecord();
        record.setHabitId(7L);
        record.setDate("2026-01-01");
        record.setNote("备注");

        HabitWithCheckIns view = new HabitWithCheckIns(habit,
                new ArrayList<>(Arrays.asList(record)));

        assertUnsupported(() -> view.records().clear());
        assertUnsupported(() -> view.dates().clear());
        assertUnsupported(() -> view.notes().clear());
        assertUnsupported(() -> view.dateList().clear());
        assertTrue(view.dates().contains("2026-01-01"));
    }

    private void assertUnsupported(Runnable action) {
        try {
            action.run();
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}
