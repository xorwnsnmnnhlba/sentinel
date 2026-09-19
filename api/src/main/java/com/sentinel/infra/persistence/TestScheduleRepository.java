package com.sentinel.infra.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.sentinel.domain.TestSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드는 보관되지 않은 항목(archivedAt IS NULL)만 대상. 보관된 스케줄은 비활성 상태이므로 실행 시각 조회에서도 제외
 */
public interface TestScheduleRepository extends JpaRepository<TestSchedule, Long> {

	List<TestSchedule> findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAt(Instant now);

	List<TestSchedule> findBySuiteIdAndArchivedAtIsNullOrderById(Long suiteId);

	List<TestSchedule> findByEnvironmentIdAndArchivedAtIsNullOrderById(Long environmentId);

	List<TestSchedule> findByArchivedAtIsNullOrderById();

	Optional<TestSchedule> findByIdAndArchivedAtIsNull(Long id);

}
