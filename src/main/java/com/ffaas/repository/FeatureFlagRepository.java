package com.ffaas.repository;

import com.ffaas.domain.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, UUID> {

    Optional<FeatureFlag> findByKey(String key);

    boolean existsByKey(String key);

    void deleteByKey(String key);
}
