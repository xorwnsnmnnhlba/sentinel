package com.sentinel.infra.persistence;

import java.util.List;

import com.sentinel.domain.TestSuite;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestSuiteRepository extends JpaRepository<TestSuite, Long> {

	List<TestSuite> findByProjectIdOrderByName(Long projectId);

}
