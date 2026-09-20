package com.sentinel.infra.persistence;

import java.util.Optional;

import com.sentinel.domain.UserToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

	Optional<UserToken> findByTokenHash(String tokenHash);

	void deleteByUserId(Long userId);

}
