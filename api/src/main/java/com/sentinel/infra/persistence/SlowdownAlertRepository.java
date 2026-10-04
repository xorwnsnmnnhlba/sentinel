package com.sentinel.infra.persistence;

import java.util.List;

import com.sentinel.domain.SlowdownAlert;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlowdownAlertRepository extends JpaRepository<SlowdownAlert, Long> {

	/** 아직 느린(회복 전) 알림 */
	List<SlowdownAlert> findByResolvedAtIsNull();

}
