package com.semantic.platform.holiday;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HolidayWindowStoreResultRepository extends JpaRepository<HolidayWindowStoreResult, Long> {
    List<HolidayWindowStoreResult> findByRunIdOrderByAlignmentAscStoreIdAsc(Long runId);
    List<HolidayWindowStoreResult> findByRunIdAndAlignmentOrderByStoreIdAsc(Long runId, String alignment);
    long countByRunId(Long runId);
}
