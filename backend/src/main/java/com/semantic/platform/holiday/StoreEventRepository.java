package com.semantic.platform.holiday;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface StoreEventRepository extends JpaRepository<StoreEvent, Long> {
    List<StoreEvent> findByStoreIdOrderByFromDateAsc(String storeId);
    List<StoreEvent> findByStoreIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(
            String storeId, LocalDate a, LocalDate b);
}
