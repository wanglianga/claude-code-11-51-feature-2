package com.semantic.platform.holiday;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HolidayWindowRunRepository extends JpaRepository<HolidayWindowRun, Long> {
    List<HolidayWindowRun> findAllByOrderByIdDesc();
    List<HolidayWindowRun> findByHolidayKeyOrderByIdDesc(String holidayKey);
}
