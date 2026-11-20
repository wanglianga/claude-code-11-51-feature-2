package com.semantic.platform.holiday;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 窗口计算规则单元测试：财务周对齐、节日阶段对齐、闭店/覆盖缺口/迁址/营业日补齐。
 */
class HolidayWindowCalculatorTest {

    private static final LocalDate FROM = LocalDate.of(2024, 12, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 30);

    // ---------- 夹具 ----------

    private static class Fixture {
        final Map<String, Map<LocalDate, HolidayWindowCalculator.CalendarDay>> calendar = new HashMap<>();
        final Map<String, List<StoreScheduleVersion>> schedules = new HashMap<>();
        final Map<String, List<StoreEvent>> events = new HashMap<>();
        final Map<String, Map<Integer, HolidayCalendarEntry>> holidays = new HashMap<>();
        final Map<String, Map<String, Map<LocalDate, HolidayWindowCalculator.CoverageInfo>>> coverage = new HashMap<>();
        final Map<String, Map<LocalDate, HolidayWindowCalculator.SalesCell>> sales = new HashMap<>();

        Fixture store(String storeId, String region) {
            calendar.put(storeId, new HashMap<>());
            for (LocalDate d = FROM; !d.isAfter(TO); d = d.plusDays(1)) {
                calendar.get(storeId).put(d, new HolidayWindowCalculator.CalendarDay(d, "open", null, null));
            }
            for (String source : List.of("orders", "refunds")) {
                coverage.computeIfAbsent(source, k -> new HashMap<>())
                        .computeIfAbsent(storeId, k -> new HashMap<>());
                for (LocalDate d = FROM; !d.isAfter(TO); d = d.plusDays(1)) {
                    coverage.get(source).get(storeId).put(d,
                            new HolidayWindowCalculator.CoverageInfo(source, storeId, d, null, null, "全量批次"));
                }
            }
            return this;
        }

        Fixture sales(String storeId, int base) {
            Map<LocalDate, HolidayWindowCalculator.SalesCell> map = new HashMap<>();
            for (LocalDate d = FROM; !d.isAfter(TO); d = d.plusDays(1)) {
                int amt = base + Math.floorMod(d.getDayOfYear() * 7 + storeId.hashCode(), 50);
                map.put(d, new HolidayWindowCalculator.SalesCell(BigDecimal.valueOf(amt), "ok", null));
            }
            sales.put(storeId, map);
            return this;
        }

        Fixture holiday(String region, int year, LocalDate start, LocalDate end) {
            HolidayCalendarEntry e = new HolidayCalendarEntry();
            e.setRegion(region);
            e.setHolidayKey("CNY");
            e.setHolidayYear(year);
            e.setStartDate(start);
            e.setEndDate(end);
            e.setName("春节");
            holidays.computeIfAbsent(region + "|CNY", k -> new HashMap<>()).put(year, e);
            return this;
        }

        Fixture close(String storeId, LocalDate from, LocalDate to, String status) {
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                calendar.get(storeId).put(d,
                        new HolidayWindowCalculator.CalendarDay(d, status, BigDecimal.ZERO, "样例"));
            }
            return this;
        }

        Fixture partial(String storeId, LocalDate date, String source, String hours) {
            coverage.get(source).get(storeId).put(date,
                    new HolidayWindowCalculator.CoverageInfo(source, storeId, date,
                            new BigDecimal(hours), new BigDecimal("13"), "仅覆盖部分小时"));
            return this;
        }

        Fixture event(String storeId, String type, LocalDate from, LocalDate to) {
            StoreEvent ev = new StoreEvent();
            ev.setStoreId(storeId);
            ev.setEventType(type);
            ev.setFromDate(from);
            ev.setToDate(to);
            events.computeIfAbsent(storeId, k -> new ArrayList<>()).add(ev);
            return this;
        }

        HolidayWindowCalculator calculator(int globalSpan) {
            return new HolidayWindowCalculator(HolidayWindowCalculator.Config.defaults(),
                    calendar, schedules, events, holidays, coverage, sales, FROM, TO, globalSpan);
        }
    }

    @Test
    void fiscalWeeksAlignAcrossYearsWithSameWeekdayComposition() {
        Fixture f = new Fixture()
                .store("S001", "NORTH").sales("S001", 800)
                .store("S002", "NORTH").sales("S002", 600)
                .holiday("NORTH", 2025, LocalDate.of(2025, 1, 27), LocalDate.of(2025, 1, 31))
                .holiday("NORTH", 2026, LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 20));
        HolidayWindowCalculator.Evaluation ev = f.calculator(1)
                .evaluate("S001", "NORTH", "CNY", 2025, 2026, "fiscal_week");
        assertTrue(ev.status().startsWith("comparable"), ev.reason());
        assertEquals(ev.baseBusinessDays(), ev.compareBusinessDays());
        assertEquals(0, (ev.baseWindowStart().toEpochDay() - FiscalCalendar.weekStart(ev.baseWindowStart()).toEpochDay()));
        assertEquals(0, (ev.compareWindowStart().toEpochDay() - FiscalCalendar.weekStart(ev.compareWindowStart()).toEpochDay()));
        // 整周窗口：每年都是 7 的倍数
        assertEquals(0, ev.baseBusinessDays() % 7);
    }

    @Test
    void holidayPhaseAlignsByBusinessDayCount() {
        Fixture f = new Fixture()
                .store("S005", "SOUTH").sales("S005", 700)
                .holiday("SOUTH", 2025, LocalDate.of(2025, 1, 29), LocalDate.of(2025, 2, 1))
                .holiday("SOUTH", 2026, LocalDate.of(2026, 2, 19), LocalDate.of(2026, 2, 23));
        HolidayWindowCalculator.Evaluation ev = f.calculator(2)
                .evaluate("S005", "SOUTH", "CNY", 2025, 2026, "holiday_phase");
        assertTrue(ev.status().startsWith("comparable"), ev.reason());
        assertEquals(ev.baseBusinessDays(), ev.compareBusinessDays());
        assertEquals(14 + 4 + 1 + 14, ev.baseBusinessDays());
        assertTrue(ev.flags().get("holidaySplitAcrossWeeks").equals(true),
                "SOUTH 2026 春节跨财务周应被标记");
    }

    @Test
    void closedDaysAreExcludedButNotZeroFilled() {
        // S001 2026 节前临时闭店 4 天：闭店不参与合计，窗口按营业日重建补齐
        Fixture f = new Fixture()
                .store("S001", "NORTH").sales("S001", 800)
                .holiday("NORTH", 2025, LocalDate.of(2025, 1, 27), LocalDate.of(2025, 1, 31))
                .holiday("NORTH", 2026, LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 20))
                .close("S001", LocalDate.of(2026, 2, 9), LocalDate.of(2026, 2, 12), "temp_closed");
        // 财务周窗口固定 4 周：比较期闭店 4 天导致营业日少 4 天，需向后补足
        HolidayWindowCalculator.Evaluation fiscal = f.calculator(1)
                .evaluate("S001", "NORTH", "CNY", 2025, 2026, "fiscal_week");
        assertTrue(fiscal.status().startsWith("comparable"), fiscal.reason());
        assertEquals(4, fiscal.compareClosedDays());
        assertEquals(fiscal.baseBusinessDays(), fiscal.compareBusinessDays());
        assertTrue(fiscal.rebuilds().stream().anyMatch(r -> "extend_forward".equals(r.kind())),
                "应记录向后补足营业日的重建步骤");
        // 节日阶段窗口按营业日计数：闭店被跳过、边界顺延，营业日数天然一致
        HolidayWindowCalculator.Evaluation phase = f.calculator(1)
                .evaluate("S001", "NORTH", "CNY", 2025, 2026, "holiday_phase");
        assertEquals(4, phase.compareClosedDays());
        assertEquals(phase.baseBusinessDays(), phase.compareBusinessDays());
        assertEquals(33, phase.compareBusinessDays());
    }

    @Test
    void partialHourCoverageIsAGapAndExitsCommonSample() {
        Fixture f = new Fixture()
                .store("S002", "NORTH").sales("S002", 640)
                .holiday("NORTH", 2025, LocalDate.of(2025, 1, 27), LocalDate.of(2025, 1, 31))
                .holiday("NORTH", 2026, LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 20))
                .partial("S002", LocalDate.of(2026, 2, 18), "orders", "6");
        HolidayWindowCalculator.Evaluation ev = f.calculator(1)
                .evaluate("S002", "NORTH", "CNY", 2025, 2026, "fiscal_week");
        assertEquals("excluded", ev.status());
        assertTrue(ev.reason().contains("覆盖缺口"), ev.reason());
        assertTrue(ev.gaps().stream().anyMatch(g -> "partial_hours".equals(g.gapType())));
    }

    @Test
    void relocationExitsCommonSample() {
        Fixture f = new Fixture()
                .store("S006", "SOUTH").sales("S006", 700)
                .holiday("SOUTH", 2025, LocalDate.of(2025, 1, 29), LocalDate.of(2025, 2, 1))
                .holiday("SOUTH", 2026, LocalDate.of(2026, 2, 19), LocalDate.of(2026, 2, 23))
                .event("S006", "relocation", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14));
        HolidayWindowCalculator.Evaluation ev = f.calculator(2)
                .evaluate("S006", "SOUTH", "CNY", 2025, 2026, "holiday_phase");
        assertEquals("excluded", ev.status());
        assertTrue(ev.reason().contains("迁址"), ev.reason());
    }

    @Test
    void missingRegionalHolidayDateExits() {
        Fixture f = new Fixture()
                .store("S004", "NORTH").sales("S004", 500)
                .holiday("NORTH", 2026, LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 20));
        HolidayWindowCalculator.Evaluation ev = f.calculator(1)
                .evaluate("S004", "NORTH", "CNY", 2025, 2026, "fiscal_week");
        assertEquals("excluded", ev.status());
        assertTrue(ev.reason().contains("未登记"), ev.reason());
    }

    @Test
    void periodComparisonNotCausalIncrement() {
        Fixture f = new Fixture()
                .store("S001", "NORTH").sales("S001", 800)
                .holiday("NORTH", 2025, LocalDate.of(2025, 1, 27), LocalDate.of(2025, 1, 31))
                .holiday("NORTH", 2026, LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 20));
        HolidayWindowCalculator.Evaluation ev = f.calculator(1)
                .evaluate("S001", "NORTH", "CNY", 2025, 2026, "fiscal_week");
        assertNotNull(ev.changeRatio());
        assertEquals(ev.compareSales().subtract(ev.baseSales()), ev.changeAbs());
    }

    @Test
    void fiscalCalendarUsesSundayStartAnd454Periods() {
        // 2026-02-16 是周一，所在周从 2026-02-15（周日）开始
        FiscalCalendar.WeekInfo w1 = FiscalCalendar.weekOf(LocalDate.of(2026, 2, 16));
        assertEquals(DayOfWeek.SUNDAY, w1.weekStart().getDayOfWeek());
        FiscalCalendar.WeekInfo w2 = FiscalCalendar.weekOf(LocalDate.of(2026, 2, 21));
        assertEquals(w1.label(), w2.label());
        assertEquals(w1.week() + 1, FiscalCalendar.weekOf(LocalDate.of(2026, 2, 23)).week());
        // 4-5-4：季内前 4 周属 P1，接着 5 周属 P2，再 4 周属 P3
        int[] periods = {1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 3,
                         4, 4, 4, 4, 5, 5, 5, 5, 5, 6, 6, 6, 6};
        for (int i = 0; i < periods.length; i++) {
            assertEquals(periods[i], FiscalCalendar.fiscalPeriodOfWeek(i + 1),
                    "第 " + (i + 1) + " 个财务周应属期间 " + periods[i]);
        }
    }
}
