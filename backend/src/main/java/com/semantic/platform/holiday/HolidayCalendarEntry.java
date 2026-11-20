package com.semantic.platform.holiday;

import jakarta.persistence.*;

import java.time.LocalDate;

/** 某版本内某地区某年某节日的获批日期区间。 */
@Entity
@Table(name = "holiday_calendar_entry")
public class HolidayCalendarEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_id", nullable = false)
    private Long versionId;

    @Column(nullable = false)
    private String region;

    @Column(name = "holiday_key", nullable = false)
    private String holidayKey;

    @Column(name = "holiday_year", nullable = false)
    private Integer holidayYear;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private String name;

    @Column(name = "approval_ref")
    private String approvalRef;

    @Column(nullable = false)
    private Boolean approved = true;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getHolidayKey() { return holidayKey; }
    public void setHolidayKey(String holidayKey) { this.holidayKey = holidayKey; }
    public Integer getHolidayYear() { return holidayYear; }
    public void setHolidayYear(Integer holidayYear) { this.holidayYear = holidayYear; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getApprovalRef() { return approvalRef; }
    public void setApprovalRef(String approvalRef) { this.approvalRef = approvalRef; }
    public Boolean getApproved() { return approved; }
    public void setApproved(Boolean approved) { this.approved = approved; }
}
