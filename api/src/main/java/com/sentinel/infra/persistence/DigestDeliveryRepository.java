package com.sentinel.infra.persistence;

import java.time.Instant;

import com.sentinel.domain.DigestDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DigestDeliveryRepository extends JpaRepository<DigestDelivery, Long> {

	boolean existsByScheduledAt(Instant scheduledAt);

}
