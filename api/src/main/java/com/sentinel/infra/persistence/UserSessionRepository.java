package com.sentinel.infra.persistence;

import java.time.Instant;
import java.util.Optional;

import com.sentinel.domain.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

	Optional<UserSession> findByTokenHash(String tokenHash);

	void deleteByTokenHash(String tokenHash);

	void deleteByUserId(Long userId);

	/** 만료된 세션 삭제 (만료되면 인증에 쓰이지 않음). 한 번에 삭제 */
	@Modifying
	@Query("delete from UserSession s where s.expiresAt <= :now")
	int deleteExpired(@Param("now") Instant now);

}
