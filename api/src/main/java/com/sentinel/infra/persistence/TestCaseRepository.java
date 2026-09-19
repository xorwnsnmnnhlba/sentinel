package com.sentinel.infra.persistence;

import java.util.List;

import com.sentinel.domain.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

	List<TestCase> findBySuiteIdOrderById(Long suiteId);

	List<TestCase> findAllByOrderById();

}
