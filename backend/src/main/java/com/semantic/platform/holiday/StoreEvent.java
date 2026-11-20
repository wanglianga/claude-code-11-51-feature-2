package com.semantic.platform.holiday;

import jakarta.persistence.*;

import java.time.LocalDate;

/** 门店事件：迁址、临时停业、缩短营业、营业制度变化。 */
@Entity
@Table(name = "store_event")
public class StoreEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "event_type", nullable = false)
    private String eventType; // relocation | temp_closure | hours_reduction | regime_change

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    /** hours_reduction 事件期间每日缩短后的营业小时；NULL 表示未指定。 */
    @Column(name = "reduced_hours")
    private java.math.BigDecimal reducedHours;

    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public LocalDate getFromDate() { return fromDate; }
    public void setFromDate(LocalDate fromDate) { this.fromDate = fromDate; }
    public LocalDate getToDate() { return toDate; }
    public void setToDate(LocalDate toDate) { this.toDate = toDate; }
    public java.math.BigDecimal getReducedHours() { return reducedHours; }
    public void setReducedHours(java.math.BigDecimal reducedHours) { this.reducedHours = reducedHours; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
