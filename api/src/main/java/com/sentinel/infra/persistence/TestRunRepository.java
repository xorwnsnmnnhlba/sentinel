package com.sentinel.infra.persistence;

import java.util.List;

import com.sentinel.domain.TestRun;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조건이 여럿인 조회와 진행 중 실행의 컬럼 단위 변경은 {@link TestRunQueries} (QueryDSL)
 */
public interface TestRunRepository extends JpaRepository<TestRun, Long>, TestRunQueries {

	List<TestRun> findByStatus(TestRun.Status status);

}
