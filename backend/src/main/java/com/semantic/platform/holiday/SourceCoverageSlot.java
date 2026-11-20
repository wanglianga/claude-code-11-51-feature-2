package com.semantic.platform.holiday;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 门店日级覆盖时段：某来源当天只覆盖部分小时时登记，未覆盖时段不能按零填充。 */
@Entity
@Table(name = "source_coverage_slot")
public class SourceCoverageSlot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "covered_hours", nullable = false)
    private BigDecimal coveredHours;

    @Column(name = "expected_hours")
    private BigDecimal expectedHours;

    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public LocalDate getBusinessDate() { return businessDate; }
    public void setBusinessDate(LocalDate businessDate) { this.businessDate = businessDate; }
    public BigDecimal getCoveredHours() { return coveredHours; }
    public void setCoveredHours(BigDecimal coveredHours) { this.coveredHours = coveredHours; }
    public BigDecimal getExpectedHours() { return expectedHours; }
    public void setExpectedHours(BigDecimal expectedHours) { this.expectedHours = expectedHours; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
