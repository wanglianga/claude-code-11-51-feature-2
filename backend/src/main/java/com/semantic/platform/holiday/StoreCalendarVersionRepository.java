package com.semantic.platform.holiday;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoreCalendarVersionRepository extends JpaRepository<StoreCalendarVersion, Long> {
    List<StoreCalendarVersion> findAllByOrderByIdDesc();
}
