package com.semantic.platform.source;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "source_batch")
public class SourceBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "store_id")
    private String storeId;

    @Column(nullable = false)
    private String status = "loaded";

    @Column(name = "record_count", nullable = false)
    private Integer recordCount = 0;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    /** 批次覆盖小时数；NULL 表示整日覆盖，非 NULL 且小于应覆盖小时表示部分覆盖。 */
    @Column(name = "covered_hours")
    private BigDecimal coveredHours;

    @Column(name = "imported_at", nullable = false)
    private OffsetDateTime importedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public Integer getRecordCount() { return recordCount; }
    public void setRecordCount(Integer recordCount) { this.recordCount = recordCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate periodStart) { this.periodStart = periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate periodEnd) { this.periodEnd = periodEnd; }
    public BigDecimal getCoveredHours() { return coveredHours; }
    public void setCoveredHours(BigDecimal coveredHours) { this.coveredHours = coveredHours; }
    public OffsetDateTime getImportedAt() { return importedAt; }
    public void setImportedAt(OffsetDateTime importedAt) { this.importedAt = importedAt; }
}
