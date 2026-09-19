package com.sentinel.infra.persistence;

import java.util.List;

import com.sentinel.domain.Environment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvironmentRepository extends JpaRepository<Environment, Long> {

	List<Environment> findByProjectIdOrderByName(Long projectId);

	List<Environment> findAllByOrderByName();

}
