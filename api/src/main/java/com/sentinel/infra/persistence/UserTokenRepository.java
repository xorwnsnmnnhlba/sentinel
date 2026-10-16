package com.sentinel.infra.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.sentinel.domain.UserToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

	Optional<UserToken> findByTokenHash(String tokenHash);

	/** 아직 쓰지 않았고 만료 전인 링크, 최근 발급(ID) 순 (예: 대기 중인 초대) */
	List<UserToken> findByTypeAndUsedAtIsNullAndExpiresAtAfterOrderByIdDesc(UserToken.Type type, Instant now);

	void deleteByUserId(Long userId);

	/**
	 * 링크 사용 처리. 아직 쓰지 않았고 만료 전일 때만 기록 (조건부 갱신). 같은 링크를 동시에 쓰거나 그사이 취소·삭제되면
	 * 나중 요청은 0건 (PostgreSQL은 먼저 갱신한 트랜잭션이 끝난 뒤 조건을 다시 확인)
	 * @return 1이면 이 요청이 사용, 0이면 사용할 수 없음
	 */
	@Modifying
	@Query("update UserToken t set t.usedAt = :now where t.id = :id and t.usedAt is null and t.expiresAt > :now")
	int markUsed(@Param("id") long id, @Param("now") Instant now);

	/** 아직 쓰지 않은 같은 종류·이메일의 링크 (예: 남은 초대) */
	long deleteByTypeAndEmailAndUsedAtIsNull(UserToken.Type type, String email);

	/** 만료된 초대·재설정 링크 삭제 (사용 여부와 무관, 만료되면 쓸 수 없음). 한 번에 삭제 */
	@Modifying
	@Query("delete from UserToken t where t.expiresAt <= :now")
	int deleteExpired(@Param("now") Instant now);

}
