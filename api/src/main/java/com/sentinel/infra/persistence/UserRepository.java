package com.sentinel.infra.persistence;

import java.util.List;
import java.util.Optional;

import com.sentinel.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드는 보관되지 않은 사용자(archivedAt IS NULL)만 대상
 */
public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByEmailAndArchivedAtIsNull(String email);

	Optional<User> findByIdAndArchivedAtIsNull(Long id);

	List<User> findByArchivedAtIsNullOrderByEmail();

	long countByArchivedAtIsNull();

}
