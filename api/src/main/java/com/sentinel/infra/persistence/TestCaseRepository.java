package com.sentinel.infra.persistence;

import java.util.List;
import java.util.Optional;

import com.sentinel.domain.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드는 보관되지 않은 항목(archivedAt IS NULL)만 대상. 실행 결과의 케이스 참조는 보관 여부와 무관하게 유지
 */
public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

	List<TestCase> findBySuiteIdAndArchivedAtIsNullOrderById(Long suiteId);

	List<TestCase> findByArchivedAtIsNullOrderById();

	Optional<TestCase> findByIdAndArchivedAtIsNull(Long id);

	long countBySuiteIdAndArchivedAtIsNull(Long suiteId);

}
