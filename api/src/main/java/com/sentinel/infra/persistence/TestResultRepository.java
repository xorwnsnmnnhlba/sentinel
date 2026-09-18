package com.sentinel.infra.persistence;

import java.util.List;

import com.sentinel.domain.TestResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestResultRepository extends JpaRepository<TestResult, Long> {

	List<TestResult> findByRunIdOrderById(Long runId);

}
