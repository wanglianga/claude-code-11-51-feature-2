package com.semantic.platform.holiday;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 移动节日同店窗口计算器（纯计算，不访问数据库）。
 *
 * 两条对齐路线，分别输出、互不混用：
 *
 * 1) fiscal_week（财务周路线）
 *    以“节日首个获批日所在财务周”为锚点，窗口 = 锚点周前后各 N 个财务周。
 *    两期窗口周数相同、星期构成一致，财务期间可直接汇总、跨店易解释。
 *    若节日横跨财务周界，节日交易会被分到相邻财务周：窗口只保留锚点周及其后 N 周，
 *    落在窗口外的节日日与“节日跨周”事实在 flags/notes 中显式暴露。
 *
 * 2) holiday_phase（节日阶段路线）
 *    以各地获批节日日期为锚点，向节前/节后各扩展 N 个“营业日”（按门店营业日历计数）。
 *    更贴近节日需求阶段、两期营业日数可比；但窗口会跨越财务周，且地方日期/营业日历维护成本高。
 */
public class HolidayWindowCalculator {

    /** 产品配置。 */
    public record Config(int preBusinessDays, int postBusinessDays, int beforeWeeks, int afterWeeks) {
        public static Config defaults() { return new Config(14, 14, 1, 1); }
    }

    public record CalendarDay(LocalDate date, String status, BigDecimal openHours, String note) {
        public boolean open() { return "open".equals(status); }
    }

    /** 来源覆盖证据：某门店某日某来源实际覆盖的小时数与应覆盖小时数。 */
    public record CoverageInfo(String source, String storeId, LocalDate date,
                               BigDecimal coveredHours, BigDecimal expectedHours, String note) {}

    /** 指标运行（净销售额）的逐日结果。 */
    public record SalesCell(BigDecimal value, String status, String message) {}

    /** 覆盖缺口：只记录“来源未覆盖/部分覆盖/指标运行未覆盖”，闭店按可解释状态单独记录，不当作零销售。 */
    public record Gap(LocalDate date, String storeId, String source, String gapType,
                      BigDecimal coveredHours, BigDecimal expectedHours, String message) {}

    /** 窗口重建说明：因闭店/营业制度变化/营业日不足而调整窗口边界。 */
    public record RebuildStep(String window, String kind, LocalDate fromBoundary, LocalDate toBoundary,
                              int businessDays, String reason) {}

    public record Window(LocalDate start, LocalDate end, List<FiscalCalendar.WeekInfo> weeks,
                         boolean truncatedByScanCap) {
        public Window(LocalDate start, LocalDate end, List<FiscalCalendar.WeekInfo> weeks) {
            this(start, end, weeks, false);
        }
    }

    private static final int MAX_PHASE_SCAN_DAYS = 60;

    public record Evaluation(
            String alignment,
            String storeId,
            String region,
            String status,
            String reason,
            LocalDate baseHolidayStart,
            LocalDate baseHolidayEnd,
            LocalDate compareHolidayStart,
            LocalDate compareHolidayEnd,
            LocalDate baseWindowStart,
            LocalDate baseWindowEnd,
            LocalDate compareWindowStart,
            LocalDate compareWindowEnd,
            int baseBusinessDays,
            int compareBusinessDays,
            int baseClosedDays,
            int compareClosedDays,
            BigDecimal baseSales,
            BigDecimal compareSales,
            BigDecimal baseHolidaySales,
            BigDecimal compareHolidaySales,
            List<Gap> gaps,
            List<RebuildStep> rebuilds,
            Map<String, Object> flags,
            List<String> notes) {

        public BigDecimal changeAbs() {
            if (status.startsWith("excluded")) return null;
            return compareSales.subtract(baseSales);
        }

        public BigDecimal changeRatio() {
            if (status.startsWith("excluded")) return null;
            if (baseSales == null || baseSales.compareTo(BigDecimal.ZERO) == 0) return null;
            return compareSales.subtract(baseSales).divide(baseSales, 6, RoundingMode.HALF_UP);
        }
    }

    private final Config config;
    private final Map<String, Map<LocalDate, CalendarDay>> calendarDays;
    private final Map<String, List<StoreScheduleVersion>> schedules;
    private final Map<String, List<StoreEvent>> events;
    private final Map<String, Map<Integer, HolidayCalendarEntry>> holidayEntries; // region|key -> year -> entry
    private final Map<String, Map<String, Map<LocalDate, CoverageInfo>>> coverage; // source -> store -> date
    private final Map<String, Map<LocalDate, SalesCell>> sales; // store -> date
    private final LocalDate barrierStart;
    private final LocalDate barrierEnd;
    /** 本次运行统一的节日财务周跨度（取所有纳入地区/年份的最大值），保证各店窗口结构一致。 */
    private final int fiscalWeekSpan;

    public HolidayWindowCalculator(Config config,
                                   Map<String, Map<LocalDate, CalendarDay>> calendarDays,
                                   Map<String, List<StoreScheduleVersion>> schedules,
                                   Map<String, List<StoreEvent>> events,
                                   Map<String, Map<Integer, HolidayCalendarEntry>> holidayEntries,
                                   Map<String, Map<String, Map<LocalDate, CoverageInfo>>> coverage,
                                   Map<String, Map<LocalDate, SalesCell>> sales,
                                   LocalDate barrierStart, LocalDate barrierEnd, int fiscalWeekSpan) {
        this.config = config;
        this.calendarDays = calendarDays;
        this.schedules = schedules;
        this.events = events;
        this.holidayEntries = holidayEntries;
        this.coverage = coverage;
        this.sales = sales;
        this.barrierStart = barrierStart;
        this.barrierEnd = barrierEnd;
        this.fiscalWeekSpan = Math.max(1, fiscalWeekSpan);
    }

    public Evaluation evaluate(String storeId, String region, String holidayKey, int baseYear, int compareYear,
                               String alignment) {
        HolidayCalendarEntry baseEntry = holidayEntry(region, holidayKey, baseYear);
        HolidayCalendarEntry compareEntry = holidayEntry(region, holidayKey, compareYear);
        if (baseEntry == null || compareEntry == null) {
            String missing = baseEntry == null ? String.valueOf(baseYear) : String.valueOf(compareYear);
            return excluded(alignment, storeId, region, baseEntry, compareEntry, null, null,
                    "该地区（" + region + "）未登记 " + baseYear + "/" + compareYear
                            + " 年获批节日日期（缺 " + missing + " 年），无法构造可比窗口");
        }

        boolean fiscal = "fiscal_week".equals(alignment);
        Window baseWindow = fiscal ? fiscalWeekWindow(baseEntry) : phaseWindow(storeId, baseEntry);
        Window compareWindow = fiscal ? fiscalWeekWindow(compareEntry) : phaseWindow(storeId, compareEntry);
        int baseWeeks = baseWindow.weeks.size();
        int compareWeeks = compareWindow.weeks.size();

        // 迁址：分析区间内迁址则新旧址商圈不同，退出共同样本。
        List<StoreEvent> relocation = relocationEvents(storeId, baseWindow.start, compareWindow.end);
        if (!relocation.isEmpty()) {
            StoreEvent ev = relocation.get(0);
            return excluded(alignment, storeId, region, baseEntry, compareEntry, baseWindow, compareWindow,
                    "分析区间内门店迁址（" + ev.getFromDate() + " ~ " + ev.getToDate()
                            + "），新旧址不可比，退出共同样本；需按新址重新登记营业日历后另起运行");
        }

        Assessment base = assess(storeId, baseWindow, baseEntry);
        Assessment compare = assess(storeId, compareWindow, compareEntry);

        List<RebuildStep> rebuilds = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Object> flags = new LinkedHashMap<>();
        int baseHolidayWeeks = holidayWeekCount(baseEntry);
        int compareHolidayWeeks = holidayWeekCount(compareEntry);
        flags.put("baseHolidayFiscalWeeks", holidayWeekLabels(baseEntry));
        flags.put("compareHolidayFiscalWeeks", holidayWeekLabels(compareEntry));
        flags.put("holidaySplitAcrossWeeks", baseHolidayWeeks > 1 || compareHolidayWeeks > 1);
        flags.put("baseWindowFiscalWeeks", weekLabels(baseWindow));
        flags.put("compareWindowFiscalWeeks", weekLabels(compareWindow));
        flags.put("baseWindowWeeks", baseWeeks);
        flags.put("compareWindowWeeks", compareWeeks);
        flags.put("fiscalWeekCountMismatch", fiscal && baseWeeks != compareWeeks);
        flags.put("analysisBarrier", barrierStart + " ~ " + barrierEnd);
        flags.put("regionalDatePolicy", "按门店所在地区获批节日日期锚定；不同地区日期不同，不跨店拼接窗口");
        boolean scheduleChanged = scheduleChanged(storeId, baseWindow, compareWindow);
        flags.put("scheduleRegimeChanged", scheduleChanged);
        int baseReduced = shortenedDays(storeId, baseWindow);
        int compareReduced = shortenedDays(storeId, compareWindow);
        flags.put("baseReducedHoursDays", baseReduced);
        flags.put("compareReducedHoursDays", compareReduced);
        if (scheduleChanged) {
            notes.add("基准期与比较期的星期营业制度不同（如周末营业小时调整），窗口按各自营业日重建，星期构成不可完全一致");
        }
        if (Boolean.TRUE.equals(flags.get("holidaySplitAcrossWeeks"))) {
            notes.add("节日横跨财务周界：" + String.join("、", holidayWeekLabels(baseEntry))
                    + " vs " + String.join("、", holidayWeekLabels(compareEntry))
                    + "；财务周路线按锚点周对齐会把节日交易分到不同财务周，节日阶段路线按日期锚定不受此影响");
        }
        if (Boolean.TRUE.equals(flags.get("fiscalWeekCountMismatch"))) {
            notes.add("财务周窗口周数不一致（" + baseWeeks + " 周 vs " + compareWeeks
                    + " 周），两期可比交易天数不同，变化率须结合营业日合计解读");
        }
        if (!fiscal) {
            boolean baseTruncated = baseWindow.truncatedByScanCap();
            boolean compareTruncated = compareWindow.truncatedByScanCap();
            flags.put("phaseWindowTruncatedByBarrier", baseTruncated || compareTruncated);
            if (baseTruncated || compareTruncated) {
                notes.add("节日阶段窗口触及分析区间边界，节前/节后营业日未足额展开");
            }
            if (base.closedDays > 0 || compare.closedDays > 0) {
                notes.add("节日阶段窗口按营业日计数：基准期闭店 " + base.closedDays + " 天、比较期闭店 "
                        + compare.closedDays + " 天，闭店跳过并顺延窗口边界，不计销售也不填零");
            }
        }

        // 核心节日不可观察（节日当天闭店/迁址）直接退出。
        if (!base.coreMissing.isEmpty() || !compare.coreMissing.isEmpty()) {
            String reason = "节日核心日期不可观察：" + String.join("；",
                    !base.coreMissing.isEmpty() ? base.coreMissing : compare.coreMissing);
            return build(alignment, storeId, region, "excluded", reason, baseEntry, compareEntry,
                    base, compare, baseWindow, compareWindow, rebuilds, flags, notes);
        }
        if (base.businessDays.isEmpty() || compare.businessDays.isEmpty()) {
            String which = base.businessDays.isEmpty() ? "基准期" : "比较期";
            Assessment empty = base.businessDays.isEmpty() ? base : compare;
            return build(alignment, storeId, region, "excluded",
                    which + "窗口内无可观察营业日（" + empty.emptyReason + "），跨期新开/长期闭店不填零、退出共同样本",
                    baseEntry, compareEntry, base, compare, baseWindow, compareWindow, rebuilds, flags, notes);
        }

        // 未覆盖时段不能按零填充：窗口内存在覆盖缺口即退出共同样本。
        List<Gap> gaps = new ArrayList<>(base.gaps);
        gaps.addAll(compare.gaps);
        if (!gaps.isEmpty()) {
            return build(alignment, storeId, region, "excluded",
                    "窗口内存在来源覆盖缺口 " + gaps.size() + " 个店日（未覆盖时段不按零填充），退出共同样本",
                    baseEntry, compareEntry, base, compare, baseWindow, compareWindow, rebuilds, flags, notes);
        }

        boolean rebuilt = false;
        int originalBaseDays = base.businessDays.size();
        int originalCompareDays = compare.businessDays.size();
        // 两条路线都要求两期可观察营业日数一致（闭店不等于零销售）；
        // 差额按覆盖完整的营业日补齐并记录重建步骤；无法补齐则退出共同样本。
        if (originalBaseDays != originalCompareDays) {
            boolean baseShorter = originalBaseDays < originalCompareDays;
            int need = Math.abs(originalBaseDays - originalCompareDays);
            String cause = closureCause(base, compare);
            if (baseShorter) {
                LocalDate newStart = extendBackward(storeId, baseWindow.start, need);
                if (newStart == null) {
                    return build(alignment, storeId, region, "excluded",
                            "基准期营业日比比较期少 " + need + " 天（" + cause + "），可用覆盖店日不足以补齐，退出共同样本",
                            baseEntry, compareEntry, base, compare, baseWindow, compareWindow, rebuilds, flags, notes);
                }
                rebuilds.add(new RebuildStep("base", "extend_backward", newStart, baseWindow.start,
                        need, "基准期原营业日 " + originalBaseDays + " 天、比较期 " + originalCompareDays
                        + " 天；" + cause + "；向前补足 " + need + " 个覆盖完整的营业日"));
                baseWindow = new Window(newStart, baseWindow.end, baseWindow.weeks);
                base = assess(storeId, baseWindow, baseEntry);
                rebuilt = true;
            } else {
                LocalDate newEnd = extendForward(storeId, compareWindow.end, need);
                if (newEnd == null) {
                    return build(alignment, storeId, region, "excluded",
                            "比较期营业日比基准期少 " + need + " 天（" + cause + "），可用覆盖店日不足以补齐，退出共同样本",
                            baseEntry, compareEntry, base, compare, baseWindow, compareWindow, rebuilds, flags, notes);
                }
                rebuilds.add(new RebuildStep("compare", "extend_forward", compareWindow.end, newEnd,
                        need, "比较期原营业日 " + originalCompareDays + " 天、基准期 " + originalBaseDays
                        + " 天；" + cause + "；向后补足 " + need + " 个覆盖完整的营业日"));
                compareWindow = new Window(compareWindow.start, newEnd, compareWindow.weeks);
                compare = assess(storeId, compareWindow, compareEntry);
                rebuilt = true;
            }
            if (!base.gaps.isEmpty() || !compare.gaps.isEmpty()) {
                return build(alignment, storeId, region, "excluded",
                        "窗口补齐时遇到覆盖缺口（不按零填充），退出共同样本",
                        baseEntry, compareEntry, base, compare, baseWindow, compareWindow, rebuilds, flags, notes);
            }
            if (base.businessDays.size() != compare.businessDays.size()) {
                return build(alignment, storeId, region, "excluded",
                        "营业日数仍不一致（" + base.businessDays.size() + " vs " + compare.businessDays.size() + "），退出共同样本",
                        baseEntry, compareEntry, base, compare, baseWindow, compareWindow, rebuilds, flags, notes);
            }
        }

        flags.put("baseBusinessDays", base.businessDays.size());
        flags.put("compareBusinessDays", compare.businessDays.size());
        if (baseReduced > 0 || compareReduced > 0) {
            notes.add("窗口内存在缩短营业日：基准期 " + baseReduced + " 天、比较期 " + compareReduced
                    + " 天，应覆盖小时按缩短后营业小时核验，未覆盖时段不按零填充");
            flags.put("reducedHoursMismatch", baseReduced != compareReduced);
        }
        boolean caveat = rebuilt || scheduleChanged || baseReduced != compareReduced
                || Boolean.TRUE.equals(flags.get("holidaySplitAcrossWeeks"))
                || Boolean.TRUE.equals(flags.get("fiscalWeekCountMismatch"))
                || Boolean.TRUE.equals(flags.get("phaseWindowTruncatedByBarrier"));
        if (rebuilt) {
            notes.add("窗口已重建：" + describeRebuilds(rebuilds) + "；重建后的净销售额仍为期间对比");
        }
        if (!base.closures.isEmpty() || !compare.closures.isEmpty()) {
            notes.add("窗口内可解释闭店不计销售也不填零：基准期 " + base.closures.size()
                    + " 天、比较期 " + compare.closures.size() + " 天");
        }
        String status = caveat ? "comparable_with_caveat" : "comparable";
        String reason = caveat
                ? "可比较但需说明：" + String.join("；", notes)
                : "两期窗口营业日数一致、来源完整；结果为期间对比，非节日因果增量";
        return build(alignment, storeId, region, status, reason, baseEntry, compareEntry,
                base, compare, baseWindow, compareWindow, rebuilds, flags, notes);
    }

    // ---------------- 窗口构造 ----------------

    /**
     * 财务周路线：锚点 = 节日首个获批日所在财务周，向节日前后各取 N 个财务周；
     * 节日跨周时两年统一取“两期节日周跨度的较大者”，保证两期窗口周数与星期构成一致。
     */
    private Window fiscalWeekWindow(HolidayCalendarEntry entry) {
        LocalDate anchor = FiscalCalendar.weekStart(entry.getStartDate());
        int totalWeeks = config.beforeWeeks() + fiscalWeekSpan + config.afterWeeks();
        LocalDate start = anchor.minusWeeks(config.beforeWeeks());
        LocalDate end = start.plusWeeks(totalWeeks).minusDays(1);
        List<FiscalCalendar.WeekInfo> weeks = new ArrayList<>();
        LocalDate w = start;
        while (!w.isAfter(end)) {
            weeks.add(FiscalCalendar.weekOf(w));
            w = w.plusWeeks(1);
        }
        return new Window(start, end, weeks, false);
    }

    /** 节日阶段路线：以获批节日日期为锚点，向两侧各扩展 N 个营业日（扫描上限 60 天，避免长期闭店把窗口拖到数年之外）。 */
    private Window phaseWindow(String storeId, HolidayCalendarEntry entry) {
        LocalDate start = entry.getStartDate();
        LocalDate end = entry.getEndDate();
        LocalDate windowStart = start;
        boolean truncated = false;
        if (config.preBusinessDays() > 0) {
            int count = 0;
            LocalDate d = start.minusDays(1);
            LocalDate scanFloor = start.minusDays(MAX_PHASE_SCAN_DAYS);
            while (count < config.preBusinessDays()) {
                if (d.isBefore(barrierStart) || d.isBefore(scanFloor)) { truncated = true; break; }
                if (isOpen(storeId, d)) count++;
                d = d.minusDays(1);
            }
            windowStart = d.plusDays(1);
        }
        LocalDate windowEnd = end;
        if (config.postBusinessDays() > 0) {
            int count = 0;
            LocalDate d = end.plusDays(1);
            LocalDate scanCeil = end.plusDays(MAX_PHASE_SCAN_DAYS);
            while (count < config.postBusinessDays()) {
                if (d.isAfter(barrierEnd) || d.isAfter(scanCeil)) { truncated = true; break; }
                if (isOpen(storeId, d)) count++;
                d = d.plusDays(1);
            }
            windowEnd = d.minusDays(1);
        }
        List<FiscalCalendar.WeekInfo> weeks = new ArrayList<>();
        LocalDate w = FiscalCalendar.weekStart(windowStart);
        while (!w.isAfter(windowEnd)) {
            weeks.add(FiscalCalendar.weekOf(w));
            w = w.plusWeeks(1);
        }
        return new Window(windowStart, windowEnd, weeks, truncated);
    }

    private boolean isOpen(String storeId, LocalDate date) {
        CalendarDay day = calendarDays.getOrDefault(storeId, Map.of()).get(date);
        return day != null && day.open();
    }

    private LocalDate extendBackward(String storeId, LocalDate start, int need) {
        List<LocalDate> picked = new ArrayList<>();
        LocalDate d = start.minusDays(1);
        while (picked.size() < need) {
            if (d.isBefore(barrierStart)) return null;
            CalendarDay cal = calendarDays.getOrDefault(storeId, Map.of()).get(d);
            if (cal == null) return null;
            if (cal.open()) {
                if (!fullyCovered(storeId, d, cal)) return null;
                picked.add(d);
            }
            d = d.minusDays(1);
        }
        return picked.get(picked.size() - 1);
    }

    private LocalDate extendForward(String storeId, LocalDate end, int need) {
        List<LocalDate> picked = new ArrayList<>();
        LocalDate d = end.plusDays(1);
        while (picked.size() < need) {
            if (d.isAfter(barrierEnd)) return null;
            CalendarDay cal = calendarDays.getOrDefault(storeId, Map.of()).get(d);
            if (cal == null) return null;
            if (cal.open()) {
                if (!fullyCovered(storeId, d, cal)) return null;
                picked.add(d);
            }
            d = d.plusDays(1);
        }
        return picked.get(picked.size() - 1);
    }

    private boolean fullyCovered(String storeId, LocalDate date, CalendarDay cal) {
        BigDecimal expected = expectedHours(storeId, date, cal);
        for (String source : List.of("orders", "refunds")) {
            CoverageInfo cov = coverage.getOrDefault(source, Map.of())
                    .getOrDefault(storeId, Map.of()).get(date);
            if (cov == null) return false;
            if (cov.coveredHours() != null && cov.coveredHours().compareTo(expected) < 0) return false;
        }
        SalesCell sc = sales.getOrDefault(storeId, Map.of()).get(date);
        return sc != null && sc.value() != null && !"not_covered".equals(sc.status());
    }

    // ---------------- 窗口评估 ----------------

    private static class Assessment {
        List<Gap> gaps = new ArrayList<>();
        List<Map<String, Object>> closures = new ArrayList<>();
        List<LocalDate> businessDays = new ArrayList<>();
        List<String> coreMissing = new ArrayList<>();
        BigDecimal sales = BigDecimal.ZERO;
        BigDecimal holidaySales = BigDecimal.ZERO;
        int closedDays = 0;
        String emptyReason;
    }

    private Assessment assess(String storeId, Window window, HolidayCalendarEntry entry) {
        Assessment a = new Assessment();
        TreeSet<LocalDate> holidayDates = new TreeSet<>();
        for (LocalDate d = entry.getStartDate(); !d.isAfter(entry.getEndDate()); d = d.plusDays(1)) {
            holidayDates.add(d);
        }
        int holidayDaysInWindow = 0;
        for (LocalDate d = window.start; !d.isAfter(window.end); d = d.plusDays(1)) {
            CalendarDay cal = calendarDays.getOrDefault(storeId, Map.of()).get(d);
            if (cal == null) {
                a.gaps.add(new Gap(d, storeId, null, "no_calendar", null, null,
                        "营业日历未登记该店日，营业状态不可解释"));
                continue;
            }
            boolean holidayDay = holidayDates.contains(d);
            if (!cal.open()) {
                a.closedDays++;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("date", d.toString());
                c.put("status", cal.status());
                c.put("note", cal.note());
                a.closures.add(c);
                if (holidayDay) a.coreMissing.add("节日日 " + d + " 门店状态为 " + cal.status());
                continue;
            }
            boolean ok = true;
            BigDecimal expected = expectedHours(storeId, d, cal);
            for (String source : List.of("orders", "refunds")) {
                CoverageInfo cov = coverage.getOrDefault(source, Map.of())
                        .getOrDefault(storeId, Map.of()).get(d);
                if (cov == null || (cov.coveredHours() != null && cov.coveredHours().compareTo(BigDecimal.ZERO) <= 0)) {
                    a.gaps.add(new Gap(d, storeId, source, "no_coverage", BigDecimal.ZERO, expected,
                            source + " 来源未覆盖该营业日（未到批次/补数中），不按零销售计入"));
                    ok = false;
                } else if (cov.coveredHours() != null && cov.coveredHours().compareTo(expected) < 0) {
                    a.gaps.add(new Gap(d, storeId, source, "partial_hours", cov.coveredHours(), expected,
                            source + " 仅覆盖 " + cov.coveredHours() + "/" + expected + " 小时，未覆盖时段不按零填充"));
                    ok = false;
                }
            }
            SalesCell sc = sales.getOrDefault(storeId, Map.of()).get(d);
            if (sc == null || sc.value() == null || "not_covered".equals(sc.status())) {
                a.gaps.add(new Gap(d, storeId, "metric_run", "run_not_covered", null, null,
                        "净销售额指标运行未给出该店日数值（" + (sc == null ? "无单元格" : sc.status()) + "）"));
                ok = false;
            } else if ("partial".equals(sc.status())) {
                a.gaps.add(new Gap(d, storeId, "metric_run", "run_partial", null, null,
                        "净销售额指标运行标记该店日部分覆盖，不按零填充"));
                ok = false;
            }
            if (ok) {
                a.businessDays.add(d);
                a.sales = a.sales.add(sc.value());
                if (holidayDay) {
                    a.holidaySales = a.holidaySales.add(sc.value());
                    holidayDaysInWindow++;
                }
            }
        }
        if (a.businessDays.isEmpty()) {
            a.emptyReason = "闭店/迁址 " + a.closedDays + " 天、覆盖缺口 " + a.gaps.size() + " 个店日、窗口 "
                    + window.start + "~" + window.end + "（含节日日 " + holidayDaysInWindow + " 天）";
        }
        return a;
    }

    private BigDecimal expectedHours(String storeId, LocalDate date, CalendarDay cal) {
        // 营业中但期间被缩短营业：以事件登记的缩短后小时为准，用于覆盖覆盖证据比对
        BigDecimal shortened = shortenedHours(storeId, date);
        if (cal.openHours() != null && cal.openHours().compareTo(BigDecimal.ZERO) > 0) {
            return shortened == null ? cal.openHours() : shortened.min(cal.openHours());
        }
        List<StoreScheduleVersion> list = schedules.getOrDefault(storeId, List.of());
        StoreScheduleVersion chosen = null;
        for (StoreScheduleVersion s : list) {
            if (!date.isBefore(s.getEffectiveFrom())
                    && (s.getEffectiveTo() == null || !date.isAfter(s.getEffectiveTo()))) {
                chosen = s;
            }
        }
        BigDecimal hours = null;
        if (chosen != null) {
            hours = weekdayHours(chosen, date.getDayOfWeek());
        }
        if (hours == null) hours = new BigDecimal("24");
        return shortened == null ? hours : shortened.min(hours);
    }

    /** 缩短营业事件期间登记的每日营业小时（无则返回 null）。 */
    private BigDecimal shortenedHours(String storeId, LocalDate date) {
        BigDecimal result = null;
        for (StoreEvent ev : events.getOrDefault(storeId, List.of())) {
            if (!"hours_reduction".equals(ev.getEventType()) || ev.getReducedHours() == null) continue;
            LocalDate evEnd = ev.getToDate() == null ? ev.getFromDate() : ev.getToDate();
            if (!date.isBefore(ev.getFromDate()) && !date.isAfter(evEnd)) {
                result = result == null ? ev.getReducedHours() : result.min(ev.getReducedHours());
            }
        }
        return result;
    }

    private BigDecimal weekdayHours(StoreScheduleVersion s, DayOfWeek dow) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(s.getPatternJson());
            String key = dow.name().substring(0, 3); // MON/TUE/...
            if (node.has(key)) return new BigDecimal(node.get(key).asText("0"));
        } catch (Exception ignored) { }
        return null;
    }

    private boolean scheduleChanged(String storeId, Window base, Window compare) {
        List<StoreScheduleVersion> list = schedules.getOrDefault(storeId, List.of());
        if (list.size() < 2) return false;
        String baseSig = signatureAt(list, base.start);
        String cmpSig = signatureAt(list, compare.start);
        return baseSig != null && cmpSig != null && !baseSig.equals(cmpSig);
    }

    private String signatureAt(List<StoreScheduleVersion> list, LocalDate date) {
        StoreScheduleVersion chosen = null;
        for (StoreScheduleVersion s : list) {
            if (!date.isBefore(s.getEffectiveFrom())
                    && (s.getEffectiveTo() == null || !date.isAfter(s.getEffectiveTo()))) {
                chosen = s;
            }
        }
        return chosen == null ? null : chosen.getPatternJson();
    }

    private int shortenedDays(String storeId, Window w) {
        int n = 0;
        for (LocalDate d = w.start; !d.isAfter(w.end); d = d.plusDays(1)) {
            if (shortenedHours(storeId, d) != null && isOpen(storeId, d)) n++;
        }
        return n;
    }

    private List<StoreEvent> relocationEvents(String storeId, LocalDate from, LocalDate to) {
        List<StoreEvent> out = new ArrayList<>();
        for (StoreEvent ev : events.getOrDefault(storeId, List.of())) {
            if (!"relocation".equals(ev.getEventType())) continue;
            LocalDate evEnd = ev.getToDate() == null ? ev.getFromDate() : ev.getToDate();
            if (!evEnd.isBefore(from) && !ev.getFromDate().isAfter(to)) out.add(ev);
        }
        return out;
    }

    private String closureCause(Assessment base, Assessment compare) {
        if (!base.closures.isEmpty() && !compare.closures.isEmpty()) {
            return "两期窗口均含可解释闭店（基准期 " + base.closures.size() + " 天、比较期 " + compare.closures.size() + " 天）";
        }
        if (!base.closures.isEmpty()) return "基准期窗口含可解释闭店 " + base.closures.size() + " 天";
        if (!compare.closures.isEmpty()) return "比较期窗口含可解释闭店 " + compare.closures.size() + " 天";
        return "两期星期营业制度不同";
    }

    private String describeRebuilds(List<RebuildStep> rebuilds) {
        List<String> parts = new ArrayList<>();
        for (RebuildStep r : rebuilds) {
            String direction = "extend_backward".equals(r.kind()) ? "向前补足 "
                    : "extend_forward".equals(r.kind()) ? "向后补足 " : "重建 ";
            parts.add((("base".equals(r.window()) ? "基准期" : "比较期")) + direction
                    + r.businessDays() + " 个营业日（" + r.reason() + "）");
        }
        return String.join("；", parts);
    }

    private List<String> weekLabels(Window w) {
        List<String> labels = new ArrayList<>();
        for (FiscalCalendar.WeekInfo info : w.weeks) labels.add(info.label());
        return labels;
    }

    private List<String> holidayWeekLabels(HolidayCalendarEntry e) {
        List<String> labels = new ArrayList<>();
        LocalDate w = FiscalCalendar.weekStart(e.getStartDate());
        LocalDate last = FiscalCalendar.weekStart(e.getEndDate());
        while (!w.isAfter(last)) {
            labels.add(FiscalCalendar.weekOf(w).label());
            w = w.plusWeeks(1);
        }
        return labels;
    }

    private int holidayWeekCount(HolidayCalendarEntry e) {
        return holidayWeekLabels(e).size();
    }

    public static int calendarWeekSpan(HolidayCalendarEntry e) {
        LocalDate w = FiscalCalendar.weekStart(e.getStartDate());
        LocalDate last = FiscalCalendar.weekStart(e.getEndDate());
        int n = 0;
        while (!w.isAfter(last)) {
            n++;
            w = w.plusWeeks(1);
        }
        return Math.max(1, n);
    }

    private HolidayCalendarEntry holidayEntry(String region, String holidayKey, int year) {
        return holidayEntries.getOrDefault(region + "|" + holidayKey, Map.of()).get(year);
    }

    private Evaluation excluded(String alignment, String storeId, String region,
                                HolidayCalendarEntry b, HolidayCalendarEntry c,
                                Window bw, Window cw, String reason) {
        return new Evaluation(alignment, storeId, region, "excluded", reason,
                b == null ? null : b.getStartDate(), b == null ? null : b.getEndDate(),
                c == null ? null : c.getStartDate(), c == null ? null : c.getEndDate(),
                bw == null ? null : bw.start, bw == null ? null : bw.end,
                cw == null ? null : cw.start, cw == null ? null : cw.end,
                0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(), List.of(), Map.of(), List.of());
    }

    private Evaluation build(String alignment, String storeId, String region, String status, String reason,
                             HolidayCalendarEntry b, HolidayCalendarEntry c,
                             Assessment base, Assessment compare, Window bw, Window cw,
                             List<RebuildStep> rebuilds, Map<String, Object> flags, List<String> notes) {
        List<Gap> gaps = new ArrayList<>(base.gaps);
        gaps.addAll(compare.gaps);
        return new Evaluation(alignment, storeId, region, status, reason,
                b.getStartDate(), b.getEndDate(), c.getStartDate(), c.getEndDate(),
                bw.start, bw.end, cw.start, cw.end,
                base.businessDays.size(), compare.businessDays.size(),
                base.closedDays, compare.closedDays,
                base.businessDays.isEmpty() ? null : base.sales,
                compare.businessDays.isEmpty() ? null : compare.sales,
                base.holidaySales, compare.holidaySales,
                gaps, rebuilds, flags, notes);
    }
}
