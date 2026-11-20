package com.semantic.platform.holiday;

import jakarta.persistence.*;

import java.time.LocalDate;

/** 资格版本内的门店条目。 */
@Entity
@Table(name = "store_eligibility_entry")
public class StoreEligibilityEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_id", nullable = false)
    private Long versionId;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(nullable = false)
    private String region;

    @Column(name = "eligible_from")
    private LocalDate eligibleFrom;

    @Column(name = "eligible_to")
    private LocalDate eligibleTo;

    @Column(nullable = false)
    private String status = "eligible"; // eligible | ineligible

    private String reason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public LocalDate getEligibleFrom() { return eligibleFrom; }
    public void setEligibleFrom(LocalDate eligibleFrom) { this.eligibleFrom = eligibleFrom; }
    public LocalDate getEligibleTo() { return eligibleTo; }
    public void setEligibleTo(LocalDate eligibleTo) { this.eligibleTo = eligibleTo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
