package com.sentinel.infra.persistence;

import java.time.Instant;
import java.util.List;

import com.sentinel.domain.TestSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestScheduleRepository extends JpaRepository<TestSchedule, Long> {

	List<TestSchedule> findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAt(Instant now);

	List<TestSchedule> findBySuiteIdOrderById(Long suiteId);

	List<TestSchedule> findAllByOrderById();

}
