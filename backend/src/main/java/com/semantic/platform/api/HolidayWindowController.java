package com.semantic.platform.api;

import com.semantic.platform.holiday.HolidayWindowService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 移动节日同店窗口比较数据产品 API。
 * 两条对齐路线（fiscal_week / holiday_phase）分别输出，不可混成一个序列。
 */
@RestController
@RequestMapping("/api/holiday-windows")
public class HolidayWindowController {

    private final HolidayWindowService service;

    public HolidayWindowController(HolidayWindowService service) {
        this.service = service;
    }

    /** 查看固定引用的日历版本、门店资格版本与覆盖时段。 */
    @GetMapping("/calendars")
    public Map<String, Object> calendars() {
        return service.calendars();
    }

    @GetMapping("/calendars/{versionId}/days")
    public Map<String, Object> calendarDays(@PathVariable Long versionId,
                                            @RequestParam String storeId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.storeCalendarDays(versionId, storeId, from, to);
    }

    @GetMapping("/coverage-slots")
    public Map<String, Object> coverageSlots(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.coverageSlots(from, to);
    }

    @GetMapping("/schedules")
    public Map<String, Object> schedules() {
        return service.schedules();
    }

    /** 节日日期追认/修订：生成新的节日日历版本，旧版本与引用它的运行保持原样。 */
    @PostMapping("/calendars")
    public Map<String, Object> reviseCalendar(@RequestBody Map<String, Object> body,
                                              @RequestAttribute(name = "user", required = false) String user) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) body.getOrDefault("entries", List.of());
        Long supersedesId = body.get("supersedesId") == null ? null
                : Long.valueOf(body.get("supersedesId").toString());
        Object created = service.reviseCalendar(
                (String) body.getOrDefault("name", "节日日历修订"),
                supersedesId,
                user == null ? "system" : user,
                (String) body.get("note"),
                entries);
        return Map.of("created", created);
    }

    /** 生成窗口比较运行（固定日历/资格/指标/批次快照）。 */
    @PostMapping("/runs")
    public Map<String, Object> createRun(@RequestBody Map<String, Object> body,
                                         @RequestAttribute(name = "user", required = false) String user) {
        Object alignments = body.get("alignments");
        List<String> list = new ArrayList<>();
        if (alignments instanceof List<?> l) {
            for (Object o : l) list.add(String.valueOf(o));
        } else if (alignments instanceof String s && !s.isBlank()) {
            for (String part : s.split(",")) list.add(part.trim());
        }
        Long calVersionId = Long.valueOf(String.valueOf(body.get("calendarVersionId")));
        Long storeCalVersionId = Long.valueOf(String.valueOf(body.get("storeCalendarVersionId")));
        Long eligibilityVersionId = Long.valueOf(String.valueOf(body.get("eligibilityVersionId")));
        String holidayKey = String.valueOf(body.getOrDefault("holidayKey", "CNY"));
        int baseYear = Integer.parseInt(String.valueOf(body.get("baseYear")));
        int compareYear = Integer.parseInt(String.valueOf(body.get("compareYear")));
        String metricKey = String.valueOf(body.getOrDefault("metricKey", "net_sales"));
        Integer pre = body.get("preBusinessDays") == null ? null : Integer.valueOf(body.get("preBusinessDays").toString());
        Integer post = body.get("postBusinessDays") == null ? null : Integer.valueOf(body.get("postBusinessDays").toString());
        Integer beforeWeeks = body.get("beforeWeeks") == null ? null : Integer.valueOf(body.get("beforeWeeks").toString());
        Integer afterWeeks = body.get("afterWeeks") == null ? null : Integer.valueOf(body.get("afterWeeks").toString());
        return service.createRun(holidayKey, baseYear, compareYear, list, calVersionId, storeCalVersionId,
                eligibilityVersionId, metricKey, pre, post, beforeWeeks, afterWeeks,
                user == null ? "system" : user);
    }

    @GetMapping("/runs")
    public Map<String, Object> listRuns() {
        return service.listRuns();
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> getRun(@PathVariable Long id) {
        return service.getRun(id);
    }

    /** 发布运行结果：发布后不可改写；修订须生成新运行。 */
    @PostMapping("/runs/{id}/release")
    public Map<String, Object> release(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, String> body,
                                       @RequestAttribute(name = "user", required = false) String user) {
        return service.release(id, user == null ? "system" : user, body == null ? null : body.get("note"));
    }
}
