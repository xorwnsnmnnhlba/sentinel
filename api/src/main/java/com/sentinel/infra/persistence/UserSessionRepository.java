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

	// 삭제는 모두 일괄 삭제 쿼리. 읽어 둔 행을 엔티티로 지우면 그사이 다른 요청(정리 배치·다른 로그아웃)이 먼저 지웠을 때
	// "행 수 0" 낙관적 잠금 예외로 요청이 실패함. 일괄 삭제는 없으면 0건으로 끝남

	@Modifying
	@Query("delete from UserSession s where s.id = :id")
	int deleteSession(@Param("id") long id);

	@Modifying
	@Query("delete from UserSession s where s.tokenHash = :tokenHash")
	int deleteByTokenHash(@Param("tokenHash") String tokenHash);

	@Modifying
	@Query("delete from UserSession s where s.userId = :userId")
	int deleteByUserId(@Param("userId") long userId);

	/** 만료된 세션 삭제 (만료되면 인증에 쓰이지 않음). 한 번에 삭제 */
	@Modifying
	@Query("delete from UserSession s where s.expiresAt <= :now")
	int deleteExpired(@Param("now") Instant now);

}
