package com.semantic.platform.holiday;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;

/** 门店营业制度版本：按星期几的营业小时，支持不同星期营业安排及制度变化。 */
@Entity
@Table(name = "store_schedule_version")
public class StoreScheduleVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pattern_json", nullable = false, columnDefinition = "jsonb")
    private String patternJson; // {"MON":13,...,"SUN":0}

    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }
    public String getPatternJson() { return patternJson; }
    public void setPatternJson(String patternJson) { this.patternJson = patternJson; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
