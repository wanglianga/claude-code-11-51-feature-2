package com.semantic.platform.holiday;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HolidayCalendarEntryRepository extends JpaRepository<HolidayCalendarEntry, Long> {
    List<HolidayCalendarEntry> findByVersionId(Long versionId);
    List<HolidayCalendarEntry> findByVersionIdAndHolidayKey(Long versionId, String holidayKey);
    List<HolidayCalendarEntry> findByVersionIdAndHolidayKeyAndHolidayYear(Long versionId, String holidayKey, Integer year);
    List<HolidayCalendarEntry> findByVersionIdAndRegionAndHolidayKeyAndHolidayYear(
            Long versionId, String region, String holidayKey, Integer year);
}
