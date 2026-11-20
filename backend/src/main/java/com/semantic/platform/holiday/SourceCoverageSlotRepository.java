package com.semantic.platform.holiday;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SourceCoverageSlotRepository extends JpaRepository<SourceCoverageSlot, Long> {
    List<SourceCoverageSlot> findBySourceTypeAndBusinessDateBetween(String sourceType, LocalDate from, LocalDate to);
    List<SourceCoverageSlot> findByBusinessDateBetween(LocalDate from, LocalDate to);
}
