package com.semantic.platform.holiday;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HolidayCalendarVersionRepository extends JpaRepository<HolidayCalendarVersion, Long> {
    List<HolidayCalendarVersion> findByStatusOrderByIdDesc(String status);
    List<HolidayCalendarVersion> findAllByOrderByIdDesc();
}
