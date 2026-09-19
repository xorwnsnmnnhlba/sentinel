package com.sentinel.infra.persistence;

import java.util.List;
import java.util.Optional;

import com.sentinel.domain.Environment;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드는 보관되지 않은 항목(archivedAt IS NULL)만 대상
 */
public interface EnvironmentRepository extends JpaRepository<Environment, Long> {

	List<Environment> findByProjectIdAndArchivedAtIsNullOrderByName(Long projectId);

	List<Environment> findByArchivedAtIsNullOrderByName();

	Optional<Environment> findByIdAndArchivedAtIsNull(Long id);

}
