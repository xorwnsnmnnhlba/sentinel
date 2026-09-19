package com.sentinel.infra.persistence;

import java.util.List;

import com.sentinel.domain.TestRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRunRepository extends JpaRepository<TestRun, Long> {

	List<TestRun> findByStatus(TestRun.Status status);

	/** 실행 목록. 스위트·프로젝트·환경 이름을 함께 로딩하여 행마다 추가 쿼리 방지 */
	@EntityGraph(attributePaths = { "suite", "suite.project", "environment" })
	Page<TestRun> findAllBy(Pageable pageable);

	@EntityGraph(attributePaths = { "suite", "suite.project", "environment" })
	Page<TestRun> findBySuiteId(Long suiteId, Pageable pageable);

}
