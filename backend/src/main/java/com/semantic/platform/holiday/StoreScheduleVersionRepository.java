package com.semantic.platform.holiday;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface StoreScheduleVersionRepository extends JpaRepository<StoreScheduleVersion, Long> {
    List<StoreScheduleVersion> findByStoreIdOrderByEffectiveFromAsc(String storeId);
    List<StoreScheduleVersion> findByStoreIdAndEffectiveFromLessThanEqualOrderByEffectiveFromAsc(
            String storeId, LocalDate date);
}
