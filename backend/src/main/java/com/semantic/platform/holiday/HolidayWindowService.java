package com.semantic.platform.holiday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantic.platform.metric.MetricService;
import com.semantic.platform.metric.MetricVersion;
import com.semantic.platform.run.Run;
import com.semantic.platform.run.RunCell;
import com.semantic.platform.run.RunService;
import com.semantic.platform.source.SourceBatch;
import com.semantic.platform.source.SourceBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 移动节日同店窗口比较服务。
 * 每次运行固定：营业日历版本 + 获批节日日历版本 + 门店资格版本 + 指标定义版本与指标运行 + 批次快照。
 * 晚到日历/销售修订只会生成新运行，不改写已生成/已发布运行。
 */
@Service
public class HolidayWindowService {

    private final HolidayCalendarVersionRepository calendarVersionRepo;
    private final HolidayCalendarEntryRepository calendarEntryRepo;
    private final StoreCalendarVersionRepository storeCalendarVersionRepo;
    private final StoreCalendarDayRepository storeCalendarDayRepo;
    private final StoreEligibilityVersionRepository eligibilityVersionRepo;
    private final StoreEligibilityEntryRepository eligibilityEntryRepo;
    private final StoreScheduleVersionRepository scheduleRepo;
    private final StoreEventRepository eventRepo;
    private final HolidayWindowRunRepository runRepo;
    private final HolidayWindowStoreResultRepository resultRepo;
    private final HolidayWindowReleaseRepository releaseRepo;
    private final SourceCoverageSlotRepository slotRepo;
    private final SourceBatchRepository batchRepo;
    private final MetricService metricService;
    private final RunService runService;
    private final ObjectMapper mapper = new ObjectMapper();

    public HolidayWindowService(HolidayCalendarVersionRepository calendarVersionRepo,
                                HolidayCalendarEntryRepository calendarEntryRepo,
                                StoreCalendarVersionRepository storeCalendarVersionRepo,
                                StoreCalendarDayRepository storeCalendarDayRepo,
                                StoreEligibilityVersionRepository eligibilityVersionRepo,
                                StoreEligibilityEntryRepository eligibilityEntryRepo,
                                StoreScheduleVersionRepository scheduleRepo,
                                StoreEventRepository eventRepo,
                                HolidayWindowRunRepository runRepo,
                                HolidayWindowStoreResultRepository resultRepo,
                                HolidayWindowReleaseRepository releaseRepo,
                                SourceCoverageSlotRepository slotRepo,
                                SourceBatchRepository batchRepo,
                                MetricService metricService,
                                RunService runService) {
        this.calendarVersionRepo = calendarVersionRepo;
        this.calendarEntryRepo = calendarEntryRepo;
        this.storeCalendarVersionRepo = storeCalendarVersionRepo;
        this.storeCalendarDayRepo = storeCalendarDayRepo;
        this.eligibilityVersionRepo = eligibilityVersionRepo;
        this.eligibilityEntryRepo = eligibilityEntryRepo;
        this.scheduleRepo = scheduleRepo;
        this.eventRepo = eventRepo;
        this.runRepo = runRepo;
        this.resultRepo = resultRepo;
        this.releaseRepo = releaseRepo;
        this.slotRepo = slotRepo;
        this.batchRepo = batchRepo;
        this.metricService = metricService;
        this.runService = runService;
    }

    // ---------------- 运行 ----------------

    @Transactional
    public Map<String, Object> createRun(String holidayKey, int baseYear, int compareYear, List<String> alignments,
                                         Long calendarVersionId, Long storeCalendarVersionId, Long eligibilityVersionId,
                                         String metricKey, Integer preBusinessDays, Integer postBusinessDays,
                                         Integer beforeWeeks, Integer afterWeeks, String createdBy) {
        if (holidayKey == null || holidayKey.isBlank()) {
            throw new IllegalArgumentException("holidayKey 不能为空");
        }
        if (compareYear <= baseYear) {
            throw new IllegalArgumentException("compareYear 必须晚于 baseYear（比较期 vs 基准期）");
        }
        List<String> normalised = new ArrayList<>();
        for (String a : alignments == null || alignments.isEmpty() ? List.of("fiscal_week", "holiday_phase") : alignments) {
            if (!"fiscal_week".equals(a) && !"holiday_phase".equals(a)) {
                throw new IllegalArgumentException("不支持的 alignment: " + a + "（仅 fiscal_week | holiday_phase，不能混成一个序列）");
            }
            normalised.add(a);
        }
        if (normalised.size() != new LinkedHashSet<>(normalised).size()) {
            throw new IllegalArgumentException("alignments 重复");
        }

        HolidayCalendarVersion calVersion = calendarVersionRepo.findById(calendarVersionId)
                .orElseThrow(() -> new IllegalArgumentException("节日日历版本不存在: " + calendarVersionId));
        StoreCalendarVersion storeCal = storeCalendarVersionRepo.findById(storeCalendarVersionId)
                .orElseThrow(() -> new IllegalArgumentException("门店营业日历版本不存在: " + storeCalendarVersionId));
        StoreEligibilityVersion elig = eligibilityVersionRepo.findById(eligibilityVersionId)
                .orElseThrow(() -> new IllegalArgumentException("门店资格版本不存在: " + eligibilityVersionId));

        List<HolidayCalendarEntry> entries = calendarEntryRepo.findByVersionId(calVersion.getId());
        List<HolidayCalendarEntry> holidayEntries = entries.stream()
                .filter(e -> e.getHolidayKey().equals(holidayKey)
                        && (e.getHolidayYear() == baseYear || e.getHolidayYear() == compareYear))
                .toList();
        if (holidayEntries.isEmpty()) {
            throw new IllegalArgumentException("节日日历版本 " + calVersion.getId() + " 未登记 " + holidayKey
                    + " " + compareYear + "/" + baseYear + " 的日期");
        }

        List<StoreEligibilityEntry> eligible = eligibilityEntryRepo.findByVersionId(elig.getId());
        if (eligible.isEmpty()) {
            throw new IllegalArgumentException("门店资格版本为空: " + elig.getId());
        }

        HolidayWindowCalculator.Config config = new HolidayWindowCalculator.Config(
                preBusinessDays == null ? 14 : preBusinessDays,
                postBusinessDays == null ? 14 : postBusinessDays,
                beforeWeeks == null ? 1 : beforeWeeks,
                afterWeeks == null ? 1 : afterWeeks);

        LocalDate minDate = holidayEntries.stream().map(HolidayCalendarEntry::getStartDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate maxDate = holidayEntries.stream().map(HolidayCalendarEntry::getEndDate).max(LocalDate::compareTo).orElseThrow();
        LocalDate analysisFrom = minDate.minusDays(60);
        LocalDate analysisTo = maxDate.plusDays(60);

        MetricVersion metricVersion = metricService.publishedVersion(metricKey);
        Run metricRun = runService.execute(metricKey, metricVersion.getId(), analysisFrom, analysisTo, "day", null);
        if (!"success".equals(metricRun.getStatus()) && !"partial".equals(metricRun.getStatus())) {
            throw new IllegalStateException("净销售额指标运行失败：" + metricRun.getError());
        }

        // 批次快照（运行固定引用；hash 用于判断“晚到批次”是否触发新运行）
        String snapshotJson = batchSnapshot();
        String snapshotHash = sha256(snapshotJson);

        HolidayWindowRun run = new HolidayWindowRun();
        run.setHolidayKey(holidayKey);
        run.setBaseYear(baseYear);
        run.setCompareYear(compareYear);
        run.setAlignments(String.join(",", normalised));
        run.setCalendarVersionId(calVersion.getId());
        run.setStoreCalendarVersionId(storeCal.getId());
        run.setEligibilityVersionId(elig.getId());
        run.setMetricKey(metricKey);
        run.setMetricVersionId(metricVersion.getId());
        run.setMetricRunId(metricRun.getId());
        run.setBatchSnapshotJson(snapshotJson);
        run.setBatchSnapshotHash(snapshotHash);
        Map<String, Object> configJson = new LinkedHashMap<>();
        configJson.put("preBusinessDays", config.preBusinessDays());
        configJson.put("postBusinessDays", config.postBusinessDays());
        configJson.put("beforeWeeks", config.beforeWeeks());
        configJson.put("afterWeeks", config.afterWeeks());
        configJson.put("analysisFrom", analysisFrom.toString());
        configJson.put("analysisTo", analysisTo.toString());
        configJson.put("fiscalYearStart", "02-01");
        configJson.put("fiscalWeekStart", "SUNDAY");
        configJson.put("fiscalPeriodPattern", "4-5-4");
        configJson.put("calendarStatus", calVersion.getStatus());
        configJson.put("storeCalendarStatus", storeCal.getStatus());
        configJson.put("eligibilityStatus", "approved");
        run.setConfigJson(toJson(configJson));
        run.setStatus("computed");
        run.setCreatedBy(createdBy);
        HolidayWindowRun saved = runRepo.save(run);

        // 组装计算输入
        Map<String, Map<LocalDate, HolidayWindowCalculator.CalendarDay>> calendarDays = new HashMap<>();
        for (StoreCalendarDay d : storeCalendarDayRepo.findByVersionIdAndBusinessDateBetween(
                storeCal.getId(), analysisFrom, analysisTo)) {
            calendarDays.computeIfAbsent(d.getStoreId(), k -> new HashMap<>())
                    .put(d.getBusinessDate(), new HolidayWindowCalculator.CalendarDay(
                            d.getBusinessDate(), d.getStatus(), d.getOpenHours(), d.getNote()));
        }
        Map<String, List<StoreScheduleVersion>> schedules = new HashMap<>();
        Map<String, List<StoreEvent>> events = new HashMap<>();
        for (StoreEligibilityEntry e : eligible) {
            schedules.put(e.getStoreId(), scheduleRepo.findByStoreIdOrderByEffectiveFromAsc(e.getStoreId()));
            events.put(e.getStoreId(), eventRepo.findByStoreIdOrderByFromDateAsc(e.getStoreId()));
        }
        Map<String, Map<Integer, HolidayCalendarEntry>> holidayByRegion = new HashMap<>();
        for (HolidayCalendarEntry e : holidayEntries) {
            holidayByRegion.computeIfAbsent(e.getRegion() + "|" + e.getHolidayKey(), k -> new HashMap<>())
                    .put(e.getHolidayYear(), e);
        }

        Map<String, Map<LocalDate, HolidayWindowCalculator.SalesCell>> sales = loadSales(metricRun.getId());
        Map<String, Map<String, Map<LocalDate, HolidayWindowCalculator.CoverageInfo>>> coverage =
                loadCoverage(analysisFrom, analysisTo, eligible);

        int fiscalWeekSpan = holidayEntries.stream()
                .mapToInt(HolidayWindowCalculator::calendarWeekSpan).max().orElse(1);
        HolidayWindowCalculator calc = new HolidayWindowCalculator(config, calendarDays, schedules, events,
                holidayByRegion, coverage, sales, analysisFrom, analysisTo, fiscalWeekSpan);

        Map<String, List<HolidayWindowStoreResult>> resultsByAlignment = new LinkedHashMap<>();
        for (String alignment : normalised) {
            List<HolidayWindowStoreResult> storeResults = new ArrayList<>();
            for (StoreEligibilityEntry e : eligible.stream()
                    .sorted(Comparator.comparing(StoreEligibilityEntry::getStoreId)).toList()) {
                HolidayWindowCalculator.Evaluation ev = "ineligible".equals(e.getStatus())
                        ? ineligible(alignment, e)
                        : calc.evaluate(e.getStoreId(), e.getRegion(), holidayKey, baseYear, compareYear, alignment);
                storeResults.add(toEntity(saved.getId(), ev));
            }
            resultRepo.saveAll(storeResults);
            resultsByAlignment.put(alignment, storeResults);
        }

        saved.setSummaryJson(buildSummary(normalised, resultsByAlignment, config, calVersion, storeCal, elig,
                metricRun, snapshotHash, analysisFrom, analysisTo));
        runRepo.save(saved);
        return getRun(saved.getId());
    }

    private HolidayWindowCalculator.Evaluation ineligible(String alignment, StoreEligibilityEntry e) {
        return new HolidayWindowCalculator.Evaluation(alignment, e.getStoreId(), e.getRegion(), "excluded",
                "门店资格版本标记为不纳入同店样本：" + (e.getReason() == null ? "未说明" : e.getReason()),
                null, null, null, null, null, null, null, null,
                0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(), List.of(), Map.of(), List.of());
    }

    private HolidayWindowStoreResult toEntity(Long runId, HolidayWindowCalculator.Evaluation ev) {
        HolidayWindowStoreResult r = new HolidayWindowStoreResult();
        r.setRunId(runId);
        r.setAlignment(ev.alignment());
        r.setStoreId(ev.storeId());
        r.setRegion(ev.region());
        r.setStatus(ev.status());
        r.setReason(ev.reason());
        r.setBaseHolidayStart(ev.baseHolidayStart());
        r.setBaseHolidayEnd(ev.baseHolidayEnd());
        r.setCompareHolidayStart(ev.compareHolidayStart());
        r.setCompareHolidayEnd(ev.compareHolidayEnd());
        r.setBaseWindowStart(ev.baseWindowStart());
        r.setBaseWindowEnd(ev.baseWindowEnd());
        r.setCompareWindowStart(ev.compareWindowStart());
        r.setCompareWindowEnd(ev.compareWindowEnd());
        r.setBaseBusinessDays(ev.baseBusinessDays());
        r.setCompareBusinessDays(ev.compareBusinessDays());
        r.setBaseClosedDays(ev.baseClosedDays());
        r.setCompareClosedDays(ev.compareClosedDays());
        r.setBaseSales(ev.baseSales());
        r.setCompareSales(ev.compareSales());
        r.setChangeAbs(ev.changeAbs());
        r.setChangeRatio(ev.changeRatio());
        r.setBaseHolidaySales(ev.baseHolidaySales());
        r.setCompareHolidaySales(ev.compareHolidaySales());
        r.setGapsJson(toJson(ev.gaps()));
        r.setRebuildJson(toJson(ev.rebuilds()));
        r.setFlagsJson(toJson(ev.flags()));
        r.setNotesJson(toJson(ev.notes()));
        return r;
    }

    // ---------------- 输入装配 ----------------

    private Map<String, Map<LocalDate, HolidayWindowCalculator.SalesCell>> loadSales(Long metricRunId) {
        Map<String, Map<LocalDate, HolidayWindowCalculator.SalesCell>> out = new HashMap<>();
        for (RunCell cell : runService.cells(metricRunId)) {
            String storeId = dimStore(cell.getDimsJson());
            if (storeId == null) continue;
            LocalDate d;
            try {
                d = LocalDate.parse(cell.getPeriodKey());
            } catch (Exception ex) {
                continue;
            }
            BigDecimal value = cell.getValueNum();
            if (cell.getValueDen() != null && cell.getValueDen().compareTo(BigDecimal.ZERO) != 0) {
                value = cell.getValueNum().divide(cell.getValueDen(), 6, RoundingMode.HALF_UP);
            }
            out.computeIfAbsent(storeId, k -> new HashMap<>())
                    .put(d, new HolidayWindowCalculator.SalesCell(value, cell.getCellStatus(), cell.getMessage()));
        }
        return out;
    }

    private Map<String, Map<String, Map<LocalDate, HolidayWindowCalculator.CoverageInfo>>> loadCoverage(
            LocalDate from, LocalDate to, List<StoreEligibilityEntry> eligible) {
        Map<String, Map<String, Map<LocalDate, HolidayWindowCalculator.CoverageInfo>>> out = new HashMap<>();
        Set<String> stores = new TreeSet<>();
        for (StoreEligibilityEntry e : eligible) stores.add(e.getStoreId());

        for (SourceBatch b : batchRepo.findAll()) {
            if (!"loaded".equals(b.getStatus())) continue; // backfilling 不算覆盖
            String source = mapSource(b.getSourceType());
            if (!"orders".equals(source) && !"refunds".equals(source)) continue;
            LocalDate start = b.getPeriodStart() == null ? from : b.getPeriodStart();
            LocalDate end = b.getPeriodEnd() == null ? to : b.getPeriodEnd();
            if (end.isBefore(from) || start.isAfter(to)) continue;
            List<String> targets = b.getStoreId() == null ? new ArrayList<>(stores) : List.of(b.getStoreId());
            for (LocalDate d = start.isBefore(from) ? from : start; !d.isAfter(end.isAfter(to) ? to : end); d = d.plusDays(1)) {
                for (String store : targets) {
                    if (!stores.contains(store)) continue;
                    out.computeIfAbsent(source, k -> new HashMap<>())
                            .computeIfAbsent(store, k -> new HashMap<>())
                            .put(d, new HolidayWindowCalculator.CoverageInfo(
                                    source, store, d, b.getCoveredHours(), null,
                                    "批次 #" + b.getId() + (b.getCoveredHours() == null ? " 整日覆盖" : " 覆盖 " + b.getCoveredHours() + " 小时")));
                }
            }
        }
        // 店日级覆盖时段（只覆盖部分小时）优先
        for (SourceCoverageSlot slot : slotRepo.findByBusinessDateBetween(from, to)) {
            String source = mapSource(slot.getSourceType());
            if (!"orders".equals(source) && !"refunds".equals(source)) continue;
            if (!stores.contains(slot.getStoreId())) continue;
            out.computeIfAbsent(source, k -> new HashMap<>())
                    .computeIfAbsent(slot.getStoreId(), k -> new HashMap<>())
                    .put(slot.getBusinessDate(), new HolidayWindowCalculator.CoverageInfo(
                            source, slot.getStoreId(), slot.getBusinessDate(),
                            slot.getCoveredHours(), slot.getExpectedHours(), slot.getNote()));
        }
        return out;
    }

    private String mapSource(String batchType) {
        return switch (batchType) {
            case "store_hours" -> "store_status";
            case "holidays" -> "holidays";
            default -> batchType;
        };
    }

    private String dimStore(String dimsJson) {
        if (dimsJson == null) return null;
        try {
            JsonNode node = mapper.readTree(dimsJson);
            JsonNode s = node.get("store_id");
            return s == null ? null : s.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private String batchSnapshot() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SourceBatch b : batchRepo.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("sourceType", b.getSourceType());
            m.put("storeId", b.getStoreId());
            m.put("status", b.getStatus());
            m.put("periodStart", b.getPeriodStart() == null ? null : b.getPeriodStart().toString());
            m.put("periodEnd", b.getPeriodEnd() == null ? null : b.getPeriodEnd().toString());
            m.put("recordCount", b.getRecordCount());
            m.put("coveredHours", b.getCoveredHours());
            rows.add(m);
        }
        rows.sort(Comparator.comparing(o -> String.valueOf(o.get("id"))));
        return toJson(rows);
    }

    private String buildSummary(List<String> alignments,
                                Map<String, List<HolidayWindowStoreResult>> resultsByAlignment,
                                HolidayWindowCalculator.Config config,
                                HolidayCalendarVersion calVersion, StoreCalendarVersion storeCal,
                                StoreEligibilityVersion elig, Run metricRun, String snapshotHash,
                                LocalDate analysisFrom, LocalDate analysisTo) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("product", "移动节日同店窗口比较");
        summary.put("disclaimer", "结果为期间对比（窗口净销售额变化），不是节日因果增量；节日效应与其他经营因素不可分离。");
        summary.put("alignments", alignments);
        summary.put("fixedReferences", fixedRefs(calVersion, storeCal, elig, metricRun, snapshotHash));
        summary.put("windowConfig", Map.of("preBusinessDays", config.preBusinessDays(),
                "postBusinessDays", config.postBusinessDays(),
                "beforeWeeks", config.beforeWeeks(), "afterWeeks", config.afterWeeks()));
        summary.put("analysisRange", analysisFrom + " ~ " + analysisTo);

        Map<String, Map<String, Object>> byAlignment = new LinkedHashMap<>();
        Map<String, Set<String>> commonSets = new LinkedHashMap<>();
        for (String alignment : alignments) {
            List<HolidayWindowStoreResult> rows = resultsByAlignment.get(alignment);
            List<String> comparable = new ArrayList<>();
            List<Map<String, Object>> excluded = new ArrayList<>();
            BigDecimal baseSum = BigDecimal.ZERO, compareSum = BigDecimal.ZERO;
            int gaps = 0;
            for (HolidayWindowStoreResult r : rows) {
                if (r.getStatus().startsWith("comparable")) {
                    comparable.add(r.getStoreId());
                    baseSum = baseSum.add(r.getBaseSales() == null ? BigDecimal.ZERO : r.getBaseSales());
                    compareSum = compareSum.add(r.getCompareSales() == null ? BigDecimal.ZERO : r.getCompareSales());
                    gaps += countJsonArray(r.getGapsJson());
                } else {
                    Map<String, Object> ex = new LinkedHashMap<>();
                    ex.put("storeId", r.getStoreId());
                    ex.put("region", r.getRegion());
                    ex.put("reason", r.getReason());
                    excluded.add(ex);
                }
            }
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("comparableStores", comparable);
            a.put("excludedStores", excluded);
            a.put("comparableCount", comparable.size());
            a.put("excludedCount", excluded.size());
            a.put("baseSalesTotal", baseSum);
            a.put("compareSalesTotal", compareSum);
            a.put("changeAbs", compareSum.subtract(baseSum));
            a.put("changeRatio", baseSum.compareTo(BigDecimal.ZERO) == 0 ? null
                    : compareSum.subtract(baseSum).divide(baseSum, 6, RoundingMode.HALF_UP));
            a.put("coverageGapsInComparableWindows", gaps);
            a.put("note", "只纳入来源完整且营业状态可解释的店日；闭店不等于零销售，未到批次不等于闭店；未覆盖时段不按零填充。");
            byAlignment.put(alignment, a);
            commonSets.put(alignment, new TreeSet<>(comparable));
        }
        summary.put("byAlignment", byAlignment);

        if (alignments.size() == 2) {
            Set<String> common = new TreeSet<>(commonSets.get(alignments.get(0)));
            common.retainAll(commonSets.get(alignments.get(1)));
            Set<String> onlyFirst = new TreeSet<>(commonSets.get(alignments.get(0)));
            onlyFirst.removeAll(commonSets.get(alignments.get(1)));
            Set<String> onlySecond = new TreeSet<>(commonSets.get(alignments.get(1)));
            onlySecond.removeAll(commonSets.get(alignments.get(0)));

            List<Map<String, Object>> windowDiffs = new ArrayList<>();
            Map<String, HolidayWindowStoreResult> first = index(resultsByAlignment.get(alignments.get(0)));
            Map<String, HolidayWindowStoreResult> second = index(resultsByAlignment.get(alignments.get(1)));
            for (String store : new TreeSet<>(first.keySet())) {
                HolidayWindowStoreResult a = first.get(store);
                HolidayWindowStoreResult b = second.get(store);
                if (a.getBaseWindowStart() == null || b.getBaseWindowStart() == null) continue;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("storeId", store);
                d.put("fiscalWeekBaseWindow", a.getBaseWindowStart() + "~" + a.getBaseWindowEnd());
                d.put("holidayPhaseBaseWindow", b.getBaseWindowStart() + "~" + b.getBaseWindowEnd());
                d.put("windowChanged", !a.getBaseWindowStart().equals(b.getBaseWindowStart())
                        || !a.getBaseWindowEnd().equals(b.getBaseWindowEnd())
                        || !a.getCompareWindowStart().equals(b.getCompareWindowStart())
                        || !a.getCompareWindowEnd().equals(b.getCompareWindowEnd()));
                d.put("fiscalWeekChange", a.getChangeRatio());
                d.put("holidayPhaseChange", b.getChangeRatio());
                windowDiffs.add(d);
            }
            Map<String, Object> cmp = new LinkedHashMap<>();
            cmp.put("commonStores", common);
            cmp.put("onlyInFiscalWeek", onlyFirst);
            cmp.put("onlyInHolidayPhase", onlySecond);
            cmp.put("note", "两条对齐路线分别输出，不可混成一个序列：财务周路线整周可比、财务期间可直接汇总；"
                    + "节日阶段路线按获批节日日期锚定、营业日数可比但跨越财务周。");
            cmp.put("windowDifferences", windowDiffs);
            summary.put("alignmentComparison", cmp);
        }
        return toJson(summary);
    }

    private Map<String, Object> fixedRefs(HolidayCalendarVersion cal, StoreCalendarVersion storeCal,
                                          StoreEligibilityVersion elig, Run metricRun, String hash) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("holidayCalendarVersionId", cal.getId());
        refs.put("holidayCalendarVersionName", cal.getName());
        refs.put("holidayCalendarApprovedAt", cal.getApprovedAt() == null ? null : cal.getApprovedAt().toString());
        refs.put("storeCalendarVersionId", storeCal.getId());
        refs.put("storeCalendarVersionName", storeCal.getName());
        refs.put("eligibilityVersionId", elig.getId());
        refs.put("metricKey", metricRun.getMetricKey());
        refs.put("metricVersionId", metricRun.getMetricVersionId());
        refs.put("metricRunId", metricRun.getId());
        refs.put("batchSnapshotHash", hash);
        refs.put("lockNote", "运行创建后锁定引用；晚到日历/销售修订生成新运行，不改写旧运行");
        return refs;
    }

    private Map<String, HolidayWindowStoreResult> index(List<HolidayWindowStoreResult> rows) {
        Map<String, HolidayWindowStoreResult> m = new LinkedHashMap<>();
        for (HolidayWindowStoreResult r : rows) m.put(r.getStoreId(), r);
        return m;
    }

    // ---------------- 查询 ----------------

    public Map<String, Object> listRuns() {
        String currentSnapshot = sha256(batchSnapshot());
        Long latestCalendar = calendarVersionRepo.findAllByOrderByIdDesc().stream()
                .findFirst().map(HolidayCalendarVersion::getId).orElse(null);
        Long latestStoreCalendar = storeCalendarVersionRepo.findAllByOrderByIdDesc().stream()
                .findFirst().map(StoreCalendarVersion::getId).orElse(null);
        List<Map<String, Object>> out = new ArrayList<>();
        for (HolidayWindowRun r : runRepo.findAllByOrderByIdDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("holidayKey", r.getHolidayKey());
            m.put("baseYear", r.getBaseYear());
            m.put("compareYear", r.getCompareYear());
            m.put("alignments", r.getAlignments());
            m.put("calendarVersionId", r.getCalendarVersionId());
            m.put("storeCalendarVersionId", r.getStoreCalendarVersionId());
            m.put("eligibilityVersionId", r.getEligibilityVersionId());
            m.put("metricKey", r.getMetricKey());
            m.put("metricVersionId", r.getMetricVersionId());
            m.put("metricRunId", r.getMetricRunId());
            m.put("batchSnapshotHash", r.getBatchSnapshotHash());
            m.put("status", r.getStatus());
            m.put("createdBy", r.getCreatedBy());
            m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
            m.put("released", releaseRepo.findByRunId(r.getId()).isPresent());
            Map<String, Object> stale = staleReasons(r, currentSnapshot, latestCalendar, latestStoreCalendar);
            m.put("stale", !stale.isEmpty());
            m.put("staleReasons", stale);
            out.add(m);
        }
        return Map.of("runs", out);
    }

    public Map<String, Object> getRun(Long id) {
        HolidayWindowRun run = runRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("窗口比较运行不存在: " + id));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", run.getId());
        out.put("holidayKey", run.getHolidayKey());
        out.put("baseYear", run.getBaseYear());
        out.put("compareYear", run.getCompareYear());
        out.put("alignments", List.of(run.getAlignments().split(",")));
        out.put("calendarVersionId", run.getCalendarVersionId());
        out.put("storeCalendarVersionId", run.getStoreCalendarVersionId());
        out.put("eligibilityVersionId", run.getEligibilityVersionId());
        out.put("metricKey", run.getMetricKey());
        out.put("metricVersionId", run.getMetricVersionId());
        out.put("metricRunId", run.getMetricRunId());
        out.put("batchSnapshotHash", run.getBatchSnapshotHash());
        out.put("batchSnapshot", parse(run.getBatchSnapshotJson()));
        out.put("config", parse(run.getConfigJson()));
        out.put("status", run.getStatus());
        out.put("summary", parse(run.getSummaryJson()));
        out.put("createdBy", run.getCreatedBy());
        out.put("createdAt", run.getCreatedAt() == null ? null : run.getCreatedAt().toString());
        out.put("released", releaseRepo.findByRunId(run.getId()).orElse(null));
        Map<String, Object> stale = staleReasons(run, sha256(batchSnapshot()),
                calendarVersionRepo.findAllByOrderByIdDesc().stream().findFirst().map(HolidayCalendarVersion::getId).orElse(null),
                storeCalendarVersionRepo.findAllByOrderByIdDesc().stream().findFirst().map(StoreCalendarVersion::getId).orElse(null));
        out.put("stale", !stale.isEmpty());
        out.put("staleReasons", stale);
        out.put("stalenessPolicy", "晚到批次/新日历版本不改写本运行；请基于最新引用新建运行，再对比两次运行的差异");
        List<Map<String, Object>> results = new ArrayList<>();
        for (HolidayWindowStoreResult r : resultRepo.findByRunIdOrderByAlignmentAscStoreIdAsc(id)) {
            results.add(resultView(r));
        }
        out.put("storeResults", results);
        return out;
    }

    private Map<String, Object> staleReasons(HolidayWindowRun run, String currentSnapshot,
                                             Long latestCalendar, Long latestStoreCalendar) {
        Map<String, Object> stale = new LinkedHashMap<>();
        if (run.getBatchSnapshotHash() != null && !run.getBatchSnapshotHash().equals(currentSnapshot)) {
            stale.put("batchSnapshotChanged", "批次快照已变化（存在晚到/补传批次），请生成新运行");
        }
        if (latestCalendar != null && !latestCalendar.equals(run.getCalendarVersionId())) {
            stale.put("newerHolidayCalendarAvailable", "存在更新的获批节日日历版本 #" + latestCalendar
                    + "（可能为节日日期追认），请生成新运行保留旧运行");
        }
        if (latestStoreCalendar != null && !latestStoreCalendar.equals(run.getStoreCalendarVersionId())) {
            stale.put("newerStoreCalendarAvailable", "存在更新的门店营业日历版本 #" + latestStoreCalendar
                    + "（可能为营业状态/迁址修订），请生成新运行");
        }
        return stale;
    }

    private Map<String, Object> resultView(HolidayWindowStoreResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("alignment", r.getAlignment());
        m.put("storeId", r.getStoreId());
        m.put("region", r.getRegion());
        m.put("status", r.getStatus());
        m.put("reason", r.getReason());
        m.put("baseHoliday", range(r.getBaseHolidayStart(), r.getBaseHolidayEnd()));
        m.put("compareHoliday", range(r.getCompareHolidayStart(), r.getCompareHolidayEnd()));
        m.put("baseWindow", range(r.getBaseWindowStart(), r.getBaseWindowEnd()));
        m.put("compareWindow", range(r.getCompareWindowStart(), r.getCompareWindowEnd()));
        m.put("baseBusinessDays", r.getBaseBusinessDays());
        m.put("compareBusinessDays", r.getCompareBusinessDays());
        m.put("baseClosedDays", r.getBaseClosedDays());
        m.put("compareClosedDays", r.getCompareClosedDays());
        m.put("baseSales", r.getBaseSales());
        m.put("compareSales", r.getCompareSales());
        m.put("changeAbs", r.getChangeAbs());
        m.put("changeRatio", r.getChangeRatio());
        m.put("baseHolidaySales", r.getBaseHolidaySales());
        m.put("compareHolidaySales", r.getCompareHolidaySales());
        m.put("gapCount", countJsonArray(r.getGapsJson()));
        m.put("rebuildCount", countJsonArray(r.getRebuildJson()));
        m.put("gaps", parse(r.getGapsJson()));
        m.put("rebuilds", parse(r.getRebuildJson()));
        m.put("flags", parse(r.getFlagsJson()));
        m.put("notes", parse(r.getNotesJson()));
        return m;
    }

    private Map<String, Object> range(LocalDate a, LocalDate b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("start", a == null ? null : a.toString());
        m.put("end", b == null ? null : b.toString());
        return m;
    }

    @Transactional
    public Map<String, Object> release(Long runId, String user, String note) {
        HolidayWindowRun run = runRepo.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("窗口比较运行不存在: " + runId));
        if (releaseRepo.findByRunId(runId).isPresent()) {
            throw new IllegalStateException("该运行已发布，发布后不可改写；如需修订请基于最新日历/数据生成新运行");
        }
        HolidayWindowRelease rel = new HolidayWindowRelease();
        rel.setRunId(runId);
        rel.setReleasedBy(user);
        rel.setNote(note);
        releaseRepo.save(rel);
        run.setStatus("released");
        runRepo.save(run);
        return getRun(runId);
    }

    // ---------------- 日历登记 ----------------

    public Map<String, Object> calendars() {
        List<Map<String, Object>> versions = new ArrayList<>();
        for (HolidayCalendarVersion v : calendarVersionRepo.findAllByOrderByIdDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("name", v.getName());
            m.put("status", v.getStatus());
            m.put("approvedBy", v.getApprovedBy());
            m.put("approvedAt", v.getApprovedAt() == null ? null : v.getApprovedAt().toString());
            m.put("supersedesId", v.getSupersedesId());
            m.put("note", v.getNote());
            m.put("entries", calendarEntryRepo.findByVersionId(v.getId()).stream().map(this::entryView).toList());
            versions.add(m);
        }
        List<Map<String, Object>> storeCalendars = new ArrayList<>();
        for (StoreCalendarVersion v : storeCalendarVersionRepo.findAllByOrderByIdDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("name", v.getName());
            m.put("status", v.getStatus());
            m.put("note", v.getNote());
            m.put("dayCount", storeCalendarDayRepo.findByVersionIdAndBusinessDateBetween(
                    v.getId(), LocalDate.of(1900, 1, 1), LocalDate.of(2999, 12, 31)).size());
            storeCalendars.add(m);
        }
        List<Map<String, Object>> eligibilities = new ArrayList<>();
        for (StoreEligibilityVersion v : eligibilityVersionRepo.findAllByOrderByIdDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("note", v.getNote());
            m.put("createdAt", v.getCreatedAt() == null ? null : v.getCreatedAt().toString());
            m.put("entries", eligibilityEntryRepo.findByVersionId(v.getId()).stream().map(e -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("storeId", e.getStoreId());
                x.put("region", e.getRegion());
                x.put("status", e.getStatus());
                x.put("reason", e.getReason());
                x.put("eligibleFrom", e.getEligibleFrom() == null ? null : e.getEligibleFrom().toString());
                x.put("eligibleTo", e.getEligibleTo() == null ? null : e.getEligibleTo().toString());
                return x;
            }).toList());
            eligibilities.add(m);
        }
        return Map.of("holidayCalendars", versions, "storeCalendars", storeCalendars, "eligibility", eligibilities);
    }

    private Map<String, Object> entryView(HolidayCalendarEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("region", e.getRegion());
        m.put("holidayKey", e.getHolidayKey());
        m.put("holidayYear", e.getHolidayYear());
        m.put("start", e.getStartDate().toString());
        m.put("end", e.getEndDate().toString());
        m.put("name", e.getName());
        m.put("approvalRef", e.getApprovalRef());
        m.put("approved", e.getApproved());
        return m;
    }

    public Map<String, Object> storeCalendarDays(Long versionId, String storeId, LocalDate from, LocalDate to) {
        storeCalendarVersionRepo.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("门店营业日历版本不存在: " + versionId));
        List<Map<String, Object>> days = new ArrayList<>();
        for (StoreCalendarDay d : storeCalendarDayRepo.findByVersionIdAndStoreIdAndBusinessDateBetween(
                versionId, storeId, from, to)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("storeId", d.getStoreId());
            m.put("date", d.getBusinessDate().toString());
            m.put("status", d.getStatus());
            m.put("openHours", d.getOpenHours());
            m.put("note", d.getNote());
            days.add(m);
        }
        return Map.of("versionId", versionId, "storeId", storeId, "days", days);
    }

    public Map<String, Object> coverageSlots(LocalDate from, LocalDate to) {
        List<SourceCoverageSlot> slots = slotRepo.findByBusinessDateBetween(from, to);
        return Map.of("slots", slots);
    }

    public Map<String, Object> schedules() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schedules", scheduleRepo.findAll());
        out.put("events", eventRepo.findAll());
        return out;
    }

    /** 追认/修订节日日期：生成新版本，旧版本与引用它的运行保持原样。 */
    @Transactional
    public HolidayCalendarVersion reviseCalendar(String name, Long supersedesId, String approvedBy, String note,
                                                  List<Map<String, Object>> entries) {
        HolidayCalendarVersion v = new HolidayCalendarVersion();
        v.setName(name);
        v.setStatus("approved");
        v.setApprovedBy(approvedBy);
        v.setApprovedAt(java.time.OffsetDateTime.now());
        v.setSupersedesId(supersedesId);
        v.setNote(note);
        v = calendarVersionRepo.save(v);
        for (Map<String, Object> e : entries) {
            HolidayCalendarEntry entry = new HolidayCalendarEntry();
            entry.setVersionId(v.getId());
            entry.setRegion((String) e.get("region"));
            entry.setHolidayKey((String) e.get("holidayKey"));
            entry.setHolidayYear(Integer.valueOf(String.valueOf(e.get("holidayYear"))));
            entry.setStartDate(LocalDate.parse(String.valueOf(e.get("start"))));
            entry.setEndDate(LocalDate.parse(String.valueOf(e.get("end"))));
            entry.setName((String) e.getOrDefault("name", (String) e.get("holidayKey")));
            entry.setApprovalRef((String) e.get("approvalRef"));
            entry.setApproved(true);
            calendarEntryRepo.save(entry);
        }
        return v;
    }

    // ---------------- 工具 ----------------

    private int countJsonArray(String json) {
        Object parsed = parse(json);
        return parsed instanceof List<?> l ? l.size() : 0;
    }

    private Object parse(String json) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
