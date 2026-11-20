package com.semantic.platform.holiday;

import jakarta.persistence.*;

import java.time.LocalDate;

/** 门店档案：地区（决定适用节日的获批日期）、开业/闭店、迁址。 */
@Entity
@Table(name = "store_profile")
public class StoreProfile {
    @Id
    @Column(name = "store_id")
    private String storeId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String region;

    private String city;

    @Column(name = "opened_on")
    private LocalDate openedOn;

    @Column(name = "closed_on")
    private LocalDate closedOn;

    @Column(nullable = false)
    private String status = "active"; // active | closed | relocated

    private String address;
    private String note;

    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public LocalDate getOpenedOn() { return openedOn; }
    public void setOpenedOn(LocalDate openedOn) { this.openedOn = openedOn; }
    public LocalDate getClosedOn() { return closedOn; }
    public void setClosedOn(LocalDate closedOn) { this.closedOn = closedOn; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
