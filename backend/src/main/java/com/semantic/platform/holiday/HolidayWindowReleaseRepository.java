package com.semantic.platform.holiday;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HolidayWindowReleaseRepository extends JpaRepository<HolidayWindowRelease, Long> {
    Optional<HolidayWindowRelease> findByRunId(Long runId);
    List<HolidayWindowRelease> findAllByOrderByIdDesc();
}
