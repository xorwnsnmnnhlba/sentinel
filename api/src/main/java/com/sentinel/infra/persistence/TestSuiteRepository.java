package com.sentinel.infra.persistence;

import java.util.List;
import java.util.Optional;

import com.sentinel.domain.TestSuite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 조회 메서드는 보관되지 않은 항목(archivedAt IS NULL)만 대상
 */
public interface TestSuiteRepository extends JpaRepository<TestSuite, Long> {

	List<TestSuite> findByProjectIdAndArchivedAtIsNullOrderByName(Long projectId);

	List<TestSuite> findByArchivedAtIsNullOrderByName();

	/** 대시보드용 전체 스위트. 프로젝트 이름을 함께 로딩하여 스위트마다 추가 쿼리 방지 */
	@Query("select s from TestSuite s join fetch s.project where s.archivedAt is null order by s.project.name, s.name")
	List<TestSuite> findActiveWithProject();

	Optional<TestSuite> findByIdAndArchivedAtIsNull(Long id);

	Optional<TestSuite> findByProjectIdAndNameAndArchivedAtIsNull(Long projectId, String name);

	/** 프로젝트 내 스위트별 사용 중인 케이스 수. 케이스가 없는 스위트는 결과에 없음 */
	@Query("""
			select c.suite.id as suiteId, count(c) as count
			from TestCase c
			where c.suite.project.id = :projectId and c.archivedAt is null
			group by c.suite.id
			""")
	List<SuiteCaseCount> countCasesByProjectId(@Param("projectId") Long projectId);

	@Query("""
			select c.suite.id as suiteId, count(c) as count
			from TestCase c
			where c.archivedAt is null
			group by c.suite.id
			""")
	List<SuiteCaseCount> countCases();

	interface SuiteCaseCount {

		Long getSuiteId();

		long getCount();

	}

}
