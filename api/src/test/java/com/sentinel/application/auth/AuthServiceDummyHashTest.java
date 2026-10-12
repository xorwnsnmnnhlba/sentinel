package com.sentinel.application.auth;

import com.sentinel.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 없는 계정의 로그인도 실제 BCrypt 비교를 거치도록 더미 해시가 올바른 형식. 형식이 틀리면 비교 없이 바로 실패해
 * 응답 시간으로 계정 유무가 드러남 (2026-10-12 독립 리뷰: 예전 값은 61자라 즉시 실패)
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuthServiceDummyHashTest {

	/** Spring Security BCryptPasswordEncoder가 비교 전에 확인하는 형식 */
	private static final String BCRYPT = "\\A\\$2[aby]?\\$\\d\\d\\$[./0-9A-Za-z]{53}";

	@Autowired
	private AuthService authService;

	@Test
	void comparesUnknownAccountsAgainstAWellFormedHash() {
		assertThat(authService.dummyHash()).matches(BCRYPT);
	}

}
