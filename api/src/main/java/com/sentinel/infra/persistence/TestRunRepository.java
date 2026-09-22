package com.sentinel.infra.persistence;

import java.time.Instant;
import java.util.List;

import com.sentinel.domain.TestRun;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TestRunRepository extends JpaRepository<TestRun, Long>, JpaSpecificationExecutor<TestRun> {

	List<TestRun> findByStatus(TestRun.Status status);

	/**
	 * 원문 정리 대상. 원문이 남아 있고 {@code cutoff} 이전에 시작한 종료된 실행을 오래된 순으로 조회 ({@code idx_run_raw_purge})
	 */
	List<TestRun> findByRawPurgedAtIsNullAndStartedAtBeforeAndStatusNotOrderByStartedAt(Instant cutoff,
			TestRun.Status excluded, Limit limit);

	/**
	 * 실행 목록. 스위트·프로젝트·환경 이름을 함께 로딩하여 행마다 추가 쿼리 방지.
	 * 조건은 {@code RunFilter}가 Specification으로 조립 (null 조건은 SQL에 포함되지 않음)
	 */
	@Override
	@EntityGraph(attributePaths = { "suite", "suite.project", "environment" })
	Page<TestRun> findAll(Specification<TestRun> spec, Pageable pageable);

}
