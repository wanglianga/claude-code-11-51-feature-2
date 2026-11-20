package com.semantic.platform.holiday;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/** 运行发布：锁定运行结果供经营会引用，发布后不改写。 */
@Entity
@Table(name = "holiday_window_release")
public class HolidayWindowRelease {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, unique = true)
    private Long runId;

    @Column(name = "released_at", nullable = false)
    private OffsetDateTime releasedAt = OffsetDateTime.now();

    @Column(name = "released_by")
    private String releasedBy;

    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public OffsetDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(OffsetDateTime releasedAt) { this.releasedAt = releasedAt; }
    public String getReleasedBy() { return releasedBy; }
    public void setReleasedBy(String releasedBy) { this.releasedBy = releasedBy; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
