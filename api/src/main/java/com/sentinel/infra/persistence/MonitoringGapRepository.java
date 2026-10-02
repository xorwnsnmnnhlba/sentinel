package com.sentinel.infra.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.sentinel.domain.MonitoringGap;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoringGapRepository extends JpaRepository<MonitoringGap, Long> {

	/** 가장 최근에 끝난 공백 */
	Optional<MonitoringGap> findFirstByOrderByEndedAtDesc();

	/** {@code start} 이상 {@code end} 미만 기간과 겹치는 공백 (최근 시작 순) */
	List<MonitoringGap> findByStartedAtLessThanAndEndedAtGreaterThanEqualOrderByStartedAtDesc(Instant end,
			Instant start);

}
