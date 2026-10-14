package com.sentinel.infra.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.sentinel.domain.UserToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

	Optional<UserToken> findByTokenHash(String tokenHash);

	/** 아직 쓰지 않았고 만료 전인 링크, 최근 발급(ID) 순 (예: 대기 중인 초대) */
	List<UserToken> findByTypeAndUsedAtIsNullAndExpiresAtAfterOrderByIdDesc(UserToken.Type type, Instant now);

	void deleteByUserId(Long userId);

	/** 아직 쓰지 않은 같은 종류·이메일의 링크 (예: 남은 초대) */
	long deleteByTypeAndEmailAndUsedAtIsNull(UserToken.Type type, String email);

}
