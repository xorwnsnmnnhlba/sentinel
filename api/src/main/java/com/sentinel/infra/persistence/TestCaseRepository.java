package com.sentinel.infra.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.sentinel.domain.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 조회 메서드는 보관되지 않은 항목(archivedAt IS NULL)만 대상. 실행 결과의 케이스 참조는 보관 여부와 무관하게 유지
 */
public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

	List<TestCase> findBySuiteIdAndArchivedAtIsNullOrderById(Long suiteId);

	List<TestCase> findByArchivedAtIsNullOrderById();

	Optional<TestCase> findByIdAndArchivedAtIsNull(Long id);

	/** 보관되지 않은 케이스를 스위트와 함께 로딩 */
	@Query("select c from TestCase c join fetch c.suite where c.id in :ids and c.archivedAt is null")
	List<TestCase> findActiveWithSuiteByIdIn(@Param("ids") Collection<Long> ids);

	Optional<TestCase> findBySuiteIdAndNameAndArchivedAtIsNull(Long suiteId, String name);

	long countBySuiteIdAndArchivedAtIsNull(Long suiteId);

}
