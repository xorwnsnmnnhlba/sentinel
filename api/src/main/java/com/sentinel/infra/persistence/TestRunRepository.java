package com.sentinel.infra.persistence;

import com.sentinel.domain.TestRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRunRepository extends JpaRepository<TestRun, Long> {

}
