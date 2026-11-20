package com.semantic.platform.holiday;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoreEligibilityVersionRepository extends JpaRepository<StoreEligibilityVersion, Long> {
    List<StoreEligibilityVersion> findAllByOrderByIdDesc();
}
