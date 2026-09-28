package com.sentinel.infra.persistence;

import java.util.List;
import java.util.Optional;

import com.sentinel.domain.TestSuite;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드는 보관되지 않은 항목(archivedAt IS NULL)만 대상
 */
public interface TestSuiteRepository extends JpaRepository<TestSuite, Long>, TestSuiteQueries {

	List<TestSuite> findByProjectIdAndArchivedAtIsNullOrderByName(Long projectId);

	List<TestSuite> findByArchivedAtIsNullOrderByName();

	Optional<TestSuite> findByIdAndArchivedAtIsNull(Long id);

	Optional<TestSuite> findByProjectIdAndNameAndArchivedAtIsNull(Long projectId, String name);

}
