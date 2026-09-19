package com.sentinel.infra.persistence;

import java.util.List;

import com.sentinel.domain.TestSuite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestSuiteRepository extends JpaRepository<TestSuite, Long> {

	List<TestSuite> findByProjectIdOrderByName(Long projectId);

	List<TestSuite> findAllByOrderByName();

	/** 프로젝트 내 스위트별 케이스 수. 케이스가 없는 스위트는 결과에 없음 */
	@Query("""
			select c.suite.id as suiteId, count(c) as count
			from TestCase c
			where c.suite.project.id = :projectId
			group by c.suite.id
			""")
	List<SuiteCaseCount> countCasesByProjectId(@Param("projectId") Long projectId);

	@Query("select c.suite.id as suiteId, count(c) as count from TestCase c group by c.suite.id")
	List<SuiteCaseCount> countCases();

	interface SuiteCaseCount {

		Long getSuiteId();

		long getCount();

	}

}
