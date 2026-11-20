package com.semantic.platform.holiday;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 窗口比较运行：固定引用的营业日历版本、获批节日日历版本、门店资格版本、
 * 指标定义版本与指标运行、批次快照。晚到日历/销售修订生成新运行，不改写旧运行。
 */
@Entity
@Table(name = "holiday_window_run")
public class HolidayWindowRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_key", nullable = false)
    private String productKey = "movable_holiday_same_store_window";

    @Column(name = "holiday_key", nullable = false)
    private String holidayKey;

    @Column(name = "base_year", nullable = false)
    private Integer baseYear;

    @Column(name = "compare_year", nullable = false)
    private Integer compareYear;

    @Column(nullable = false)
    private String alignments;

    @Column(name = "calendar_version_id", nullable = false)
    private Long calendarVersionId;

    @Column(name = "store_calendar_version_id", nullable = false)
    private Long storeCalendarVersionId;

    @Column(name = "eligibility_version_id", nullable = false)
    private Long eligibilityVersionId;

    @Column(name = "metric_key", nullable = false)
    private String metricKey;

    @Column(name = "metric_version_id", nullable = false)
    private Long metricVersionId;

    @Column(name = "metric_run_id", nullable = false)
    private Long metricRunId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "batch_snapshot_json", nullable = false, columnDefinition = "jsonb")
    private String batchSnapshotJson;

    @Column(name = "batch_snapshot_hash")
    private String batchSnapshotHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    private String configJson;

    @Column(nullable = false)
    private String status = "computed"; // computed | failed | released

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary_json", columnDefinition = "jsonb")
    private String summaryJson;

    private String error;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "locked_at", nullable = false)
    private OffsetDateTime lockedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProductKey() { return productKey; }
    public void setProductKey(String productKey) { this.productKey = productKey; }
    public String getHolidayKey() { return holidayKey; }
    public void setHolidayKey(String holidayKey) { this.holidayKey = holidayKey; }
    public Integer getBaseYear() { return baseYear; }
    public void setBaseYear(Integer baseYear) { this.baseYear = baseYear; }
    public Integer getCompareYear() { return compareYear; }
    public void setCompareYear(Integer compareYear) { this.compareYear = compareYear; }
    public String getAlignments() { return alignments; }
    public void setAlignments(String alignments) { this.alignments = alignments; }
    public Long getCalendarVersionId() { return calendarVersionId; }
    public void setCalendarVersionId(Long calendarVersionId) { this.calendarVersionId = calendarVersionId; }
    public Long getStoreCalendarVersionId() { return storeCalendarVersionId; }
    public void setStoreCalendarVersionId(Long storeCalendarVersionId) { this.storeCalendarVersionId = storeCalendarVersionId; }
    public Long getEligibilityVersionId() { return eligibilityVersionId; }
    public void setEligibilityVersionId(Long eligibilityVersionId) { this.eligibilityVersionId = eligibilityVersionId; }
    public String getMetricKey() { return metricKey; }
    public void setMetricKey(String metricKey) { this.metricKey = metricKey; }
    public Long getMetricVersionId() { return metricVersionId; }
    public void setMetricVersionId(Long metricVersionId) { this.metricVersionId = metricVersionId; }
    public Long getMetricRunId() { return metricRunId; }
    public void setMetricRunId(Long metricRunId) { this.metricRunId = metricRunId; }
    public String getBatchSnapshotJson() { return batchSnapshotJson; }
    public void setBatchSnapshotJson(String batchSnapshotJson) { this.batchSnapshotJson = batchSnapshotJson; }
    public String getBatchSnapshotHash() { return batchSnapshotHash; }
    public void setBatchSnapshotHash(String batchSnapshotHash) { this.batchSnapshotHash = batchSnapshotHash; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLockedAt() { return lockedAt; }
    public void setLockedAt(OffsetDateTime lockedAt) { this.lockedAt = lockedAt; }
}
