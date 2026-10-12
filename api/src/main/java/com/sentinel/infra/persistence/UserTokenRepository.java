package com.sentinel.infra.persistence;

import java.util.Optional;

import com.sentinel.domain.UserToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

	Optional<UserToken> findByTokenHash(String tokenHash);

	void deleteByUserId(Long userId);

	/** 아직 쓰지 않은 같은 종류·이메일의 링크 (예: 남은 초대) */
	long deleteByTypeAndEmailAndUsedAtIsNull(UserToken.Type type, String email);

}
