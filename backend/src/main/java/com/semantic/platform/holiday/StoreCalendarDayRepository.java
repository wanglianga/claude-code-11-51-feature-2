package com.semantic.platform.holiday;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface StoreCalendarDayRepository extends JpaRepository<StoreCalendarDay, Long> {
    List<StoreCalendarDay> findByVersionIdAndStoreIdAndBusinessDateBetween(
            Long versionId, String storeId, LocalDate from, LocalDate to);
    List<StoreCalendarDay> findByVersionIdAndBusinessDateBetween(
            Long versionId, LocalDate from, LocalDate to);
}
