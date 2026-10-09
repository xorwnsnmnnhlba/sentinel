package com.sentinel.infra.persistence;

import java.util.List;
import java.util.Optional;

import com.sentinel.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드는 보관되지 않은 항목(archivedAt IS NULL)만 대상. findById는 보관 여부와 무관하므로 이력 참조에만 사용
 */
public interface ProjectRepository extends JpaRepository<Project, Long> {

	Optional<Project> findByNameAndArchivedAtIsNull(String name);

	List<Project> findByArchivedAtIsNullOrderByName();

	/** Webhook을 지정한 프로젝트, 이름 순 */
	List<Project> findByArchivedAtIsNullAndWebhookUrlCipherIsNotNullOrderByName();

	Optional<Project> findByIdAndArchivedAtIsNull(Long id);

}
