package com.semantic.platform.holiday;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 版本内某门店某营业日的营业状态。 */
@Entity
@Table(name = "store_calendar_day")
public class StoreCalendarDay {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_id", nullable = false)
    private Long versionId;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(nullable = false)
    private String status; // open | closed | temp_closed | relocated

    @Column(name = "open_hours")
    private BigDecimal openHours;

    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public LocalDate getBusinessDate() { return businessDate; }
    public void setBusinessDate(LocalDate businessDate) { this.businessDate = businessDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getOpenHours() { return openHours; }
    public void setOpenHours(BigDecimal openHours) { this.openHours = openHours; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
