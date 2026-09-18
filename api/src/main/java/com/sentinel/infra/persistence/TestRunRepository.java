package com.sentinel.infra.persistence;

import java.util.List;

import com.sentinel.domain.TestRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRunRepository extends JpaRepository<TestRun, Long> {

	List<TestRun> findByStatus(TestRun.Status status);

}
