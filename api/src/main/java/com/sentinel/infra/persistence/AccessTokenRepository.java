package com.sentinel.infra.persistence;

import java.util.List;
import java.util.Optional;

import com.sentinel.domain.AccessToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessTokenRepository extends JpaRepository<AccessToken, Long> {

	Optional<AccessToken> findByTokenHash(String tokenHash);

	/** 사용자의 폐기되지 않은 토큰 (만료 포함), 최근 발급 순 */
	List<AccessToken> findByUserIdAndRevokedAtIsNullOrderByIdDesc(Long userId);

	Optional<AccessToken> findByIdAndUserIdAndRevokedAtIsNull(Long id, Long userId);

}
