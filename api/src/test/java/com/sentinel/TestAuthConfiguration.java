package com.sentinel;

import java.util.UUID;

import com.sentinel.application.auth.AuthService;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.UserRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 인증이 필요한 API를 테스트하기 위한 기본 로그인. 모든 MockMvc 요청에 관리자 세션 토큰을 붙인다.
 * <p>
 * 인증 자체를 검증하는 테스트({@code AuthTest})는 이 설정을 쓰지 않고 직접 토큰을 지정한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestAuthConfiguration {

	/** 컨텍스트마다 다른 계정을 만들어 이메일 유니크 제약과 충돌하지 않도록 함 */
	@Bean
	MockMvcBuilderCustomizer defaultSessionToken(UserRepository users, AuthService authService) {
		String email = "tester-" + UUID.randomUUID() + "@sentinel.test";
		String password = UUID.randomUUID().toString();
		users.save(new User(email, "테스터", authService.encodePassword(password), User.Role.ADMIN));
		String token = authService.login(email, password).token();
		return builder -> builder.defaultRequest(get("/").header("Authorization", "Bearer " + token));
	}

}
