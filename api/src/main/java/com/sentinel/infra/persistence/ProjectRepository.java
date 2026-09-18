package com.sentinel.infra.persistence;

import java.util.Optional;

import com.sentinel.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {

	Optional<Project> findByName(String name);

}
