package com.semantic.platform.holiday;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 逐店逐对齐路线的窗口边界、销售合计、覆盖缺口与重建/退出说明。 */
@Entity
@Table(name = "holiday_window_store_result")
public class HolidayWindowStoreResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(nullable = false)
    private String alignment; // fiscal_week | holiday_phase

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(nullable = false)
    private String region;

    @Column(nullable = false)
    private String status; // comparable | comparable_with_caveat | excluded

    private String reason;

    @Column(name = "base_holiday_start") private LocalDate baseHolidayStart;
    @Column(name = "base_holiday_end") private LocalDate baseHolidayEnd;
    @Column(name = "compare_holiday_start") private LocalDate compareHolidayStart;
    @Column(name = "compare_holiday_end") private LocalDate compareHolidayEnd;
    @Column(name = "base_window_start") private LocalDate baseWindowStart;
    @Column(name = "base_window_end") private LocalDate baseWindowEnd;
    @Column(name = "compare_window_start") private LocalDate compareWindowStart;
    @Column(name = "compare_window_end") private LocalDate compareWindowEnd;

    @Column(name = "base_business_days") private Integer baseBusinessDays;
    @Column(name = "compare_business_days") private Integer compareBusinessDays;
    @Column(name = "base_closed_days") private Integer baseClosedDays;
    @Column(name = "compare_closed_days") private Integer compareClosedDays;

    @Column(name = "base_sales") private BigDecimal baseSales;
    @Column(name = "compare_sales") private BigDecimal compareSales;
    @Column(name = "change_abs") private BigDecimal changeAbs;
    @Column(name = "change_ratio") private BigDecimal changeRatio;
    @Column(name = "base_holiday_sales") private BigDecimal baseHolidaySales;
    @Column(name = "compare_holiday_sales") private BigDecimal compareHolidaySales;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gaps_json", columnDefinition = "jsonb")
    private String gapsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rebuild_json", columnDefinition = "jsonb")
    private String rebuildJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "flags_json", columnDefinition = "jsonb")
    private String flagsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notes_json", columnDefinition = "jsonb")
    private String notesJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getAlignment() { return alignment; }
    public void setAlignment(String alignment) { this.alignment = alignment; }
    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDate getBaseHolidayStart() { return baseHolidayStart; }
    public void setBaseHolidayStart(LocalDate v) { this.baseHolidayStart = v; }
    public LocalDate getBaseHolidayEnd() { return baseHolidayEnd; }
    public void setBaseHolidayEnd(LocalDate v) { this.baseHolidayEnd = v; }
    public LocalDate getCompareHolidayStart() { return compareHolidayStart; }
    public void setCompareHolidayStart(LocalDate v) { this.compareHolidayStart = v; }
    public LocalDate getCompareHolidayEnd() { return compareHolidayEnd; }
    public void setCompareHolidayEnd(LocalDate v) { this.compareHolidayEnd = v; }
    public LocalDate getBaseWindowStart() { return baseWindowStart; }
    public void setBaseWindowStart(LocalDate v) { this.baseWindowStart = v; }
    public LocalDate getBaseWindowEnd() { return baseWindowEnd; }
    public void setBaseWindowEnd(LocalDate v) { this.baseWindowEnd = v; }
    public LocalDate getCompareWindowStart() { return compareWindowStart; }
    public void setCompareWindowStart(LocalDate v) { this.compareWindowStart = v; }
    public LocalDate getCompareWindowEnd() { return compareWindowEnd; }
    public void setCompareWindowEnd(LocalDate v) { this.compareWindowEnd = v; }
    public Integer getBaseBusinessDays() { return baseBusinessDays; }
    public void setBaseBusinessDays(Integer v) { this.baseBusinessDays = v; }
    public Integer getCompareBusinessDays() { return compareBusinessDays; }
    public void setCompareBusinessDays(Integer v) { this.compareBusinessDays = v; }
    public Integer getBaseClosedDays() { return baseClosedDays; }
    public void setBaseClosedDays(Integer v) { this.baseClosedDays = v; }
    public Integer getCompareClosedDays() { return compareClosedDays; }
    public void setCompareClosedDays(Integer v) { this.compareClosedDays = v; }
    public BigDecimal getBaseSales() { return baseSales; }
    public void setBaseSales(BigDecimal v) { this.baseSales = v; }
    public BigDecimal getCompareSales() { return compareSales; }
    public void setCompareSales(BigDecimal v) { this.compareSales = v; }
    public BigDecimal getChangeAbs() { return changeAbs; }
    public void setChangeAbs(BigDecimal v) { this.changeAbs = v; }
    public BigDecimal getChangeRatio() { return changeRatio; }
    public void setChangeRatio(BigDecimal v) { this.changeRatio = v; }
    public BigDecimal getBaseHolidaySales() { return baseHolidaySales; }
    public void setBaseHolidaySales(BigDecimal v) { this.baseHolidaySales = v; }
    public BigDecimal getCompareHolidaySales() { return compareHolidaySales; }
    public void setCompareHolidaySales(BigDecimal v) { this.compareHolidaySales = v; }
    public String getGapsJson() { return gapsJson; }
    public void setGapsJson(String v) { this.gapsJson = v; }
    public String getRebuildJson() { return rebuildJson; }
    public void setRebuildJson(String v) { this.rebuildJson = v; }
    public String getFlagsJson() { return flagsJson; }
    public void setFlagsJson(String v) { this.flagsJson = v; }
    public String getNotesJson() { return notesJson; }
    public void setNotesJson(String v) { this.notesJson = v; }
}
