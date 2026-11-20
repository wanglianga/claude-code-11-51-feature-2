package com.semantic.platform.holiday;

import com.semantic.platform.source.OrderFact;
import com.semantic.platform.source.OrderFactRepository;
import com.semantic.platform.source.RefundFact;
import com.semantic.platform.source.RefundFactRepository;
import com.semantic.platform.source.SourceBatch;
import com.semantic.platform.source.SourceBatchRepository;
import com.semantic.platform.source.StoreStatus;
import com.semantic.platform.source.StoreStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 移动节日同店窗口比较的样例数据播种：
 *  - 6 家门店，NORTH/SOUTH 两个地区，获批节日日期不同（区域日期不同）
 *  - CNY 2025 / 2026 获批日期在不同财务周（跨年跨周）；SOUTH 2026 节日横跨财务周界
 *  - Easter 2025/2026（单日，周日）
 *  - S004 跨期新开、S005 节前临时闭店、S006 迁址
 *  - S001 周末营业制度变化、S002 某日仅覆盖部分小时、S003 缺批次、S005 节日天数变化
 *  - 批次与覆盖时段证据
 */
@Component
@Order(2)
public class HolidayDemoSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(HolidayDemoSeeder.class);

    private static final LocalDate DATA_FROM = LocalDate.of(2024, 12, 1);
    private static final LocalDate DATA_TO = LocalDate.of(2026, 6, 30);

    private final HolidayCalendarVersionRepository calendarVersionRepo;
    private final HolidayCalendarEntryRepository calendarEntryRepo;
    private final StoreCalendarVersionRepository storeCalendarVersionRepo;
    private final StoreCalendarDayRepository storeCalendarDayRepo;
    private final StoreEligibilityVersionRepository eligibilityVersionRepo;
    private final StoreEligibilityEntryRepository eligibilityEntryRepo;
    private final StoreProfileRepository profileRepo;
    private final StoreScheduleVersionRepository scheduleRepo;
    private final StoreEventRepository eventRepo;
    private final SourceCoverageSlotRepository slotRepo;
    private final SourceBatchRepository batchRepo;
    private final OrderFactRepository orderRepo;
    private final RefundFactRepository refundRepo;
    private final StoreStatusRepository storeStatusRepo;

    public HolidayDemoSeeder(HolidayCalendarVersionRepository calendarVersionRepo,
                             HolidayCalendarEntryRepository calendarEntryRepo,
                             StoreCalendarVersionRepository storeCalendarVersionRepo,
                             StoreCalendarDayRepository storeCalendarDayRepo,
                             StoreEligibilityVersionRepository eligibilityVersionRepo,
                             StoreEligibilityEntryRepository eligibilityEntryRepo,
                             StoreProfileRepository profileRepo,
                             StoreScheduleVersionRepository scheduleRepo,
                             StoreEventRepository eventRepo,
                             SourceCoverageSlotRepository slotRepo,
                             SourceBatchRepository batchRepo,
                             OrderFactRepository orderRepo,
                             RefundFactRepository refundRepo,
                             StoreStatusRepository storeStatusRepo) {
        this.calendarVersionRepo = calendarVersionRepo;
        this.calendarEntryRepo = calendarEntryRepo;
        this.storeCalendarVersionRepo = storeCalendarVersionRepo;
        this.storeCalendarDayRepo = storeCalendarDayRepo;
        this.eligibilityVersionRepo = eligibilityVersionRepo;
        this.eligibilityEntryRepo = eligibilityEntryRepo;
        this.profileRepo = profileRepo;
        this.scheduleRepo = scheduleRepo;
        this.eventRepo = eventRepo;
        this.slotRepo = slotRepo;
        this.batchRepo = batchRepo;
        this.orderRepo = orderRepo;
        this.refundRepo = refundRepo;
        this.storeStatusRepo = storeStatusRepo;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (calendarVersionRepo.count() > 0) {
            log.info("移动节日窗口样例已存在，跳过播种");
            return;
        }
        log.info("开始播种移动节日窗口样例数据...");
        seedProfiles();
        seedSchedules();
        seedEvents();
        long holidayVersion = seedHolidayCalendars();
        long storeCalendarVersion = seedStoreCalendar();
        long eligibilityVersion = seedEligibility();
        seedCoverageSlots();
        seedBatches();
        seedSales();
        seedStoreStatusGaps();
        log.info("移动节日窗口样例播种完成：holidayCalendar={} storeCalendar={} eligibility={}",
                holidayVersion, storeCalendarVersion, eligibilityVersion);
    }

    private void seedProfiles() {
        profile("S001", "上海南京东路店", "NORTH", "上海", LocalDate.of(2020, 3, 1), null, "active");
        profile("S002", "上海徐家汇店", "NORTH", "上海", LocalDate.of(2021, 6, 1), null, "active");
        profile("S003", "杭州湖滨店", "NORTH", "杭州", LocalDate.of(2021, 9, 1), null, "active");
        profile("S004", "南京新街口店", "NORTH", "南京", LocalDate.of(2026, 2, 10), null, "active");
        profile("S005", "广州天河店", "SOUTH", "广州", LocalDate.of(2019, 12, 1), null, "active");
        profile("S006", "深圳福田店", "SOUTH", "深圳", LocalDate.of(2019, 5, 1), null, "relocated");
    }

    private void profile(String storeId, String name, String region, String city,
                         LocalDate openedOn, LocalDate closedOn, String status) {
        StoreProfile p = new StoreProfile();
        p.setStoreId(storeId);
        p.setName(name);
        p.setRegion(region);
        p.setCity(city);
        p.setOpenedOn(openedOn);
        p.setClosedOn(closedOn);
        p.setStatus(status);
        if ("relocated".equals(status)) {
            p.setNote("2026-01-01 迁址至新商圈，新旧址不可比");
        }
        profileRepo.save(p);
    }

    private void seedSchedules() {
        // 标准营业制度：周一至周六 13 小时，周日 12 小时
        String standard = "{\"MON\":13,\"TUE\":13,\"WED\":13,\"THU\":13,\"FRI\":13,\"SAT\":13,\"SUN\":12}";
        String[] stores = {"S001", "S002", "S003", "S004", "S005", "S006"};
        for (String s : stores) {
            schedule(s, LocalDate.of(2020, 1, 1), null, standard, "标准营业制度");
        }
        // S001 周末营业制度变化：2026-02-01 起周日缩短为 8 小时
        schedule("S001", LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 31), standard, "周日 12 小时");
        schedule("S001", LocalDate.of(2026, 2, 1), null,
                "{\"MON\":13,\"TUE\":13,\"WED\":13,\"THU\":13,\"FRI\":13,\"SAT\":13,\"SUN\":8}",
                "周末营业制度变化：周日起缩短为 8 小时");
    }

    private void schedule(String storeId, LocalDate from, LocalDate to, String pattern, String note) {
        StoreScheduleVersion s = new StoreScheduleVersion();
        s.setStoreId(storeId);
        s.setEffectiveFrom(from);
        s.setEffectiveTo(to);
        s.setPatternJson(pattern);
        s.setNote(note);
        scheduleRepo.save(s);
    }

    private void seedEvents() {
        event("S005", "temp_closure", LocalDate.of(2026, 2, 9), LocalDate.of(2026, 2, 12),
                "节前临时闭店（春节前消防安全检查，落在节前窗口内）");
        event("S006", "relocation", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14),
                "门店迁址，新旧址商圈不同");
        event("S001", "regime_change", LocalDate.of(2026, 2, 1), null, "周日营业时长由 12 小时缩短为 8 小时");
        StoreEvent reduced = new StoreEvent();
        reduced.setStoreId("S003");
        reduced.setEventType("hours_reduction");
        reduced.setFromDate(LocalDate.of(2026, 3, 30));
        reduced.setToDate(LocalDate.of(2026, 4, 3));
        reduced.setReducedHours(new BigDecimal("8"));
        reduced.setNote("复活节周缩短营业（每日 8 小时，仅比较期）");
        eventRepo.save(reduced);
    }

    private void event(String storeId, String type, LocalDate from, LocalDate to, String note) {
        StoreEvent e = new StoreEvent();
        e.setStoreId(storeId);
        e.setEventType(type);
        e.setFromDate(from);
        e.setToDate(to);
        e.setNote(note);
        eventRepo.save(e);
    }

    private long seedHolidayCalendars() {
        HolidayCalendarVersion v = new HolidayCalendarVersion();
        v.setName("2025-2026 获批节日日历 v1");
        v.setStatus("approved");
        v.setApprovedBy("admin");
        v.setApprovedAt(OffsetDateTime.now());
        v.setNote("各地获批的移动节日日期；区域不同日期不同");
        v = calendarVersionRepo.save(v);

        // NORTH：春节 2025-01-27~01-31（周一至周五）；2026-02-16~02-20（周一至周五）
        holidayEntry(v.getId(), "NORTH", "CNY", 2025, LocalDate.of(2025, 1, 27), LocalDate.of(2025, 1, 31),
                "春节", "沪府办〔2024〕18号");
        holidayEntry(v.getId(), "NORTH", "CNY", 2026, LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 20),
                "春节", "沪府办〔2025〕22号");
        // SOUTH：春节 2025-01-29~02-01（4 天）；2026-02-19~02-23（5 天，横跨财务周界）
        holidayEntry(v.getId(), "SOUTH", "CNY", 2025, LocalDate.of(2025, 1, 29), LocalDate.of(2025, 2, 1),
                "春节", "粤府办〔2024〕31号");
        holidayEntry(v.getId(), "SOUTH", "CNY", 2026, LocalDate.of(2026, 2, 19), LocalDate.of(2026, 2, 23),
                "春节", "粤府办〔2025〕35号");
        // EASTER：同一天但仍跨年移动
        for (String region : List.of("NORTH", "SOUTH")) {
            holidayEntry(v.getId(), region, "EASTER", 2025, LocalDate.of(2025, 4, 20), LocalDate.of(2025, 4, 20),
                    "复活节", "地方教会公告");
            holidayEntry(v.getId(), region, "EASTER", 2026, LocalDate.of(2026, 4, 5), LocalDate.of(2026, 4, 5),
                    "复活节", "地方教会公告");
        }
        return v.getId();
    }

    private void holidayEntry(Long versionId, String region, String key, int year,
                              LocalDate start, LocalDate end, String name, String ref) {
        HolidayCalendarEntry e = new HolidayCalendarEntry();
        e.setVersionId(versionId);
        e.setRegion(region);
        e.setHolidayKey(key);
        e.setHolidayYear(year);
        e.setStartDate(start);
        e.setEndDate(end);
        e.setName(name);
        e.setApprovalRef(ref);
        e.setApproved(true);
        calendarEntryRepo.save(e);
    }

    private long seedStoreCalendar() {
        StoreCalendarVersion v = new StoreCalendarVersion();
        v.setName("门店营业日历 v1");
        v.setStatus("approved");
        v.setCreatedBy("admin");
        v.setNote("门店本地营业日；闭店/临时停业不等同零销售");
        v = storeCalendarVersionRepo.save(v);
        List<StoreCalendarDay> days = new ArrayList<>();
        String[] stores = {"S001", "S002", "S003", "S004", "S005", "S006"};
        for (String store : stores) {
            for (LocalDate d = DATA_FROM; !d.isAfter(DATA_TO); d = d.plusDays(1)) {
                StoreCalendarDay day = new StoreCalendarDay();
                day.setVersionId(v.getId());
                day.setStoreId(store);
                day.setBusinessDate(d);
                if ("S004".equals(store) && d.isBefore(LocalDate.of(2026, 2, 10))) {
                    day.setStatus("closed");
                    day.setOpenHours(BigDecimal.ZERO);
                    day.setNote("新店尚未开业");
                } else if ("S005".equals(store) && !d.isBefore(LocalDate.of(2026, 2, 9))
                        && !d.isAfter(LocalDate.of(2026, 2, 12))) {
                    day.setStatus("temp_closed");
                    day.setOpenHours(BigDecimal.ZERO);
                    day.setNote("节前临时闭店（消防检查）");
                } else if ("S006".equals(store) && !d.isBefore(LocalDate.of(2026, 1, 1))
                        && !d.isAfter(LocalDate.of(2026, 1, 14))) {
                    day.setStatus("relocated");
                    day.setOpenHours(BigDecimal.ZERO);
                    day.setNote("迁址停业");
                } else {
                    day.setStatus("open");
                    // open_hours 留空，由营业制度版本给出应覆盖小时，避免日历与制度重复维护
                    day.setOpenHours(null);
                }
                days.add(day);
            }
        }
        storeCalendarDayRepo.saveAll(days);
        return v.getId();
    }

    private long seedEligibility() {
        StoreEligibilityVersion v = new StoreEligibilityVersion();
        v.setNote("同店样本资格：S001-S006，S004 跨期新开、S006 迁址在计算中按规则退出");
        v.setCreatedBy("admin");
        v = eligibilityVersionRepo.save(v);
        eligibility(v.getId(), "S001", "NORTH", LocalDate.of(2020, 3, 1), "eligible", "常营门店");
        eligibility(v.getId(), "S002", "NORTH", LocalDate.of(2021, 6, 1), "eligible", "常营门店");
        eligibility(v.getId(), "S003", "NORTH", LocalDate.of(2021, 9, 1), "eligible", "常营门店（2026-02 部分批次缺数）");
        eligibility(v.getId(), "S004", "NORTH", LocalDate.of(2026, 2, 10), "eligible", "跨期新开，比较期无营业日");
        eligibility(v.getId(), "S005", "SOUTH", LocalDate.of(2019, 12, 1), "eligible", "常营门店（2026 节前临时闭店）");
        eligibility(v.getId(), "S006", "SOUTH", LocalDate.of(2019, 5, 1), "eligible", "2026-01 迁址");
        return v.getId();
    }

    private void eligibility(Long versionId, String storeId, String region, LocalDate from,
                             String status, String reason) {
        StoreEligibilityEntry e = new StoreEligibilityEntry();
        e.setVersionId(versionId);
        e.setStoreId(storeId);
        e.setRegion(region);
        e.setEligibleFrom(from);
        e.setStatus(status);
        e.setReason(reason);
        eligibilityEntryRepo.save(e);
    }

    private void seedCoverageSlots() {
        // S002 2026-04-03（复活节前）订单来源仅覆盖 6/13 小时（未覆盖时段不能按零填充）
        SourceCoverageSlot slot = new SourceCoverageSlot();
        slot.setSourceType("orders");
        slot.setStoreId("S002");
        slot.setBusinessDate(LocalDate.of(2026, 4, 3));
        slot.setCoveredHours(new BigDecimal("6"));
        slot.setExpectedHours(new BigDecimal("13"));
        slot.setNote("POS 中继在复活节前仅回传 6 小时（06:00-12:00），其余时段未覆盖");
        slotRepo.save(slot);
    }

    private void seedBatches() {
        // 订单批次：2024-12 ~ 2026-06；S003 保留 2026-02-01~02-07 缺批次
        for (String store : List.of("S001", "S002", "S004", "S005", "S006")) {
            batch("orders", store, "loaded", DATA_FROM, DATA_TO, 0);
        }
        batch("orders", "S003", "loaded", DATA_FROM, LocalDate.of(2026, 1, 31), 0);
        // S003 2026-02-01~02-07 由既有 backfilling 批次表达（缺数）
        batch("orders", "S003", "backfilling", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 7), 0);
        batch("orders", "S003", "loaded", LocalDate.of(2026, 2, 8), DATA_TO, 0);
        // 退款批次：全局覆盖
        batch("refunds", null, "loaded", DATA_FROM, DATA_TO, 0);
        // 营业状态与节日批次（供其他运行引用）
        batch("store_hours", null, "loaded", DATA_FROM, DATA_TO, 0);
        batch("holidays", null, "loaded", DATA_FROM, DATA_TO, 0);
    }

    private void batch(String type, String store, String status, LocalDate start, LocalDate end, int count) {
        SourceBatch b = new SourceBatch();
        b.setSourceType(type);
        b.setStoreId(store);
        b.setStatus(status);
        b.setPeriodStart(start);
        b.setPeriodEnd(end);
        b.setRecordCount(count);
        batchRepo.save(b);
    }

    /** 在节日窗口附近生成确定性销售，用于逐店复算窗口边界与合计。 */
    private void seedSales() {
        Set<String> existing = new HashSet<>();
        for (OrderFact o : orderRepo.findAll()) existing.add(o.getOrderId());

        Map<String, Integer> baseAmount = new HashMap<>();
        baseAmount.put("S001", 850);
        baseAmount.put("S002", 640);
        baseAmount.put("S003", 520);
        baseAmount.put("S004", 480);
        baseAmount.put("S005", 610);
        baseAmount.put("S006", 700);

        List<Object[]> ranges = List.of(
                // store, from, to, 春节加成
                new Object[]{"S001", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 20), 120},
                new Object[]{"S002", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 20), 100},
                new Object[]{"S003", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 20), 90},
                new Object[]{"S005", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 20), 110},
                new Object[]{"S006", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 20), 105},
                new Object[]{"S001", LocalDate.of(2026, 1, 20), LocalDate.of(2026, 3, 10), 150},
                new Object[]{"S002", LocalDate.of(2026, 1, 20), LocalDate.of(2026, 3, 10), 130},
                new Object[]{"S003", LocalDate.of(2026, 1, 20), LocalDate.of(2026, 3, 10), 120},
                new Object[]{"S004", LocalDate.of(2026, 2, 10), LocalDate.of(2026, 3, 10), 115},
                new Object[]{"S005", LocalDate.of(2026, 1, 20), LocalDate.of(2026, 3, 10), 140},
                new Object[]{"S006", LocalDate.of(2026, 1, 20), LocalDate.of(2026, 3, 10), 145},
                // 复活节窗口
                new Object[]{"S001", LocalDate.of(2025, 3, 25), LocalDate.of(2025, 5, 10), 70},
                new Object[]{"S002", LocalDate.of(2025, 3, 25), LocalDate.of(2025, 5, 10), 60},
                new Object[]{"S003", LocalDate.of(2025, 3, 25), LocalDate.of(2025, 5, 10), 55},
                new Object[]{"S005", LocalDate.of(2025, 3, 25), LocalDate.of(2025, 5, 10), 65},
                new Object[]{"S006", LocalDate.of(2025, 3, 25), LocalDate.of(2025, 5, 10), 68},
                new Object[]{"S001", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 25), 80},
                new Object[]{"S002", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 25), 75},
                new Object[]{"S003", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 25), 70},
                new Object[]{"S004", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 25), 72},
                new Object[]{"S005", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 25), 78},
                new Object[]{"S006", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 25), 74}
        );

        Map<String, LocalDate> holidayStart = new HashMap<>();
        holidayStart.put("NORTH|2025|CNY", LocalDate.of(2025, 1, 27));
        holidayStart.put("NORTH|2026|CNY", LocalDate.of(2026, 2, 16));
        holidayStart.put("SOUTH|2025|CNY", LocalDate.of(2025, 1, 29));
        holidayStart.put("SOUTH|2026|CNY", LocalDate.of(2026, 2, 19));
        holidayStart.put("NORTH|2025|EASTER", LocalDate.of(2025, 4, 20));
        holidayStart.put("NORTH|2026|EASTER", LocalDate.of(2026, 4, 5));
        holidayStart.put("SOUTH|2025|EASTER", LocalDate.of(2025, 4, 20));
        holidayStart.put("SOUTH|2026|EASTER", LocalDate.of(2026, 4, 5));

        Map<String, Integer> refundSequences = new HashMap<>();
        for (Object[] range : ranges) {
            String store = (String) range[0];
            LocalDate from = (LocalDate) range[1];
            LocalDate to = (LocalDate) range[2];
            int bump = (Integer) range[3];
            String region = store.startsWith("S005") || store.startsWith("S006") ? "SOUTH" : "NORTH";
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                int weekdayFactor = switch (d.getDayOfWeek()) {
                    case SATURDAY -> 140;
                    case SUNDAY -> 110;
                    default -> 100;
                };
                int idx = store.hashCode() + d.getDayOfYear() * 31;
                int noise = Math.floorMod(idx, 40);
                int amount = baseAmount.get(store) * weekdayFactor / 100 + noise;
                // 节日当天加成（按地区/年份命中）
                for (Map.Entry<String, LocalDate> hs : holidayStart.entrySet()) {
                    String[] p = hs.getKey().split("\\|");
                    if (!p[0].equals(region)) continue;
                    LocalDate hStart = hs.getValue();
                    int hLen = "CNY".equals(p[1]) ? 5 : 1;
                    if (!d.isBefore(hStart) && !d.isAfter(hStart.plusDays(hLen - 1))) {
                        amount += bump;
                    }
                }
                String orderId = "HW-" + store + "-" + d;
                if (existing.contains(orderId)) continue;
                existing.add(orderId);
                OrderFact o = new OrderFact();
                o.setOrderId(orderId);
                o.setStoreId(store);
                o.setOrderedAt(d.atTime(12, 0).atOffset(ZoneOffset.UTC));
                o.setPaidAt(d.atTime(12, 1).atOffset(ZoneOffset.UTC));
                o.setBusinessDate(d);
                o.setAmount(new BigDecimal(amount));
                o.setCurrency("CNY");
                o.setStatus("completed");
                o.setChannel("pos");
                orderRepo.save(o);

                // 每 3 天一笔小额退款，验证净销售额 = 毛利 - 退款
                if (d.getDayOfMonth() % 3 == 0) {
                    String refundId = "HWR-" + store + "-" + d;
                    RefundFact r = new RefundFact();
                    r.setRefundId(refundId);
                    r.setOrderId(orderId);
                    r.setStoreId(store);
                    r.setRefundedAt(d.atTime(15, 0).atOffset(ZoneOffset.UTC));
                    r.setBusinessDate(d);
                    r.setAmount(new BigDecimal(amount / 10));
                    r.setReason("样例退货");
                    refundRepo.save(r);
                    refundSequences.merge(store, 1, Integer::sum);
                }
            }
        }
        log.info("移动节日窗口样例销售已生成：订单 {} 笔，退款 {} 笔",
                orderRepo.count(), refundRepo.count());
    }

    /** 补充 2025 与 S006 的营业状态，使指标运行能枚举完整门店宇宙。 */
    private void seedStoreStatusGaps() {
        Set<String> existing = new HashSet<>();
        for (StoreStatus s : storeStatusRepo.findAll()) {
            existing.add(s.getStoreId() + "@" + s.getBusinessDate());
        }
        List<StoreStatus> rows = new ArrayList<>();
        String[] stores = {"S001", "S002", "S003", "S004", "S005", "S006"};
        for (String store : stores) {
            for (LocalDate d = DATA_FROM; !d.isAfter(DATA_TO); d = d.plusDays(1)) {
                if (existing.contains(store + "@" + d)) continue;
                StoreStatus s = new StoreStatus();
                s.setStoreId(store);
                s.setBusinessDate(d);
                if ("S004".equals(store) && d.isBefore(LocalDate.of(2026, 2, 10))) {
                    s.setStatus("closed");
                    s.setOpenHours(BigDecimal.ZERO);
                } else if ("S005".equals(store) && !d.isBefore(LocalDate.of(2026, 2, 9))
                        && !d.isAfter(LocalDate.of(2026, 2, 12))) {
                    s.setStatus("temp_closed");
                    s.setOpenHours(BigDecimal.ZERO);
                } else if ("S006".equals(store) && !d.isBefore(LocalDate.of(2026, 1, 1))
                        && !d.isAfter(LocalDate.of(2026, 1, 14))) {
                    s.setStatus("temp_closed");
                    s.setOpenHours(BigDecimal.ZERO);
                } else {
                    s.setStatus("open");
                    s.setOpenHours(new BigDecimal("13"));
                }
                rows.add(s);
                existing.add(store + "@" + d);
            }
        }
        storeStatusRepo.saveAll(rows);
    }
}
