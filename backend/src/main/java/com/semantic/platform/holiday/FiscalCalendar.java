package com.semantic.platform.holiday;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 零售财务日历：财年自 2 月 1 日起算，财务周固定为周日至周六（周序 1 起），
 * 财务期间采用 4-5-4 结构（每季度 13 周拆为 4/5/4）。
 *
 * 这样整周窗口的星期构成跨年一致，财务期间可直接汇总；但移动节日若横跨周界，
 * 节日交易会被拆到相邻两个财务周，比较时必须显式暴露。
 */
public final class FiscalCalendar {

    public static final int FY_START_MONTH = 2;
    public static final int FY_START_DAY = 1;
    public static final DayOfWeek WEEK_START = DayOfWeek.SUNDAY;

    public record WeekInfo(int fiscalYear, int week, LocalDate weekStart, LocalDate weekEnd,
                           int fiscalPeriod, String label, String periodLabel) {}

    private FiscalCalendar() {}

    /** 某财年第一天所在财务周的周日。 */
    public static LocalDate fiscalYearStart(int fiscalYear) {
        LocalDate feb1 = LocalDate.of(fiscalYear, FY_START_MONTH, FY_START_DAY);
        int offset = feb1.getDayOfWeek().getValue() % 7; // Sunday=0, Monday=1 ... Saturday=6
        return feb1.minusDays(offset);
    }

    /** 日期所在的财务周（周日起算）。 */
    public static WeekInfo weekOf(LocalDate date) {
        int fy = date.getMonthValue() >= FY_START_MONTH ? date.getYear() : date.getYear() - 1;
        LocalDate start = fiscalYearStart(fy);
        if (date.isBefore(start)) {
            fy--;
            start = fiscalYearStart(fy);
        } else {
            LocalDate next = fiscalYearStart(fy + 1);
            if (!date.isBefore(next)) {
                fy++;
                start = next;
            }
        }
        long days = ChronoUnit.DAYS.between(start, date);
        int week = (int) (days / 7) + 1;
        LocalDate ws = weekStart(date);
        int period = fiscalPeriodOfWeek(week);
        return new WeekInfo(fy, week, ws, ws.plusDays(6), period,
                String.format("FY%d-W%02d", fy, week),
                String.format("FY%d-P%02d", fy, period));
    }

    /** 4-5-4：每季度 13 周，按 4/5/4 拆财务期间。 */
    public static int fiscalPeriodOfWeek(int week) {
        int idx = week - 1;
        if (idx < 0) return 1;
        int quarter = idx / 13;
        int within = idx % 13;
        int base = quarter * 3;
        if (within < 4) return base + 1;
        if (within < 9) return base + 2;
        return base + 3;
    }

    public static LocalDate weekStart(LocalDate date) {
        int offset = date.getDayOfWeek().getValue() % 7;
        return date.minusDays(offset);
    }

    /** 财务周区间内的全部周标签（含起止）。 */
    public static java.util.List<String> weekLabels(LocalDate start, LocalDate end) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        LocalDate d = weekStart(start);
        while (!d.isAfter(end)) {
            labels.add(weekOf(d).label());
            d = d.plusWeeks(1);
        }
        return labels;
    }
}
