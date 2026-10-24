package com.sentinel.domain;

import java.util.Map;

import com.sentinel.runner.InvalidValueException;
import com.sentinel.runner.InvalidValueException.Code;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 도메인 검증 오류의 종류·값. 화면이 한국어 문장을 만드는 데 씀 (web/lib/input-error.ts). 영어 메시지는 각 도메인 테스트에서 확인
 */
class InvalidValueCodeTest {

	private final Project project = new Project("billing", null);

	@Test
	void namesBlankAndTooLongValues() {
		assertCode(() -> new Project("  ", null), Code.REQUIRED, Map.of("target", "name"));
		assertCode(() -> new Project("a".repeat(101), null), Code.TOO_LONG, Map.of("target", "name", "max", "100"));
		assertCode(() -> new Environment(project, "dev", " "), Code.REQUIRED, Map.of("target", "baseUrl"));
	}

	@Test
	void saysWhatIsWrongWithABaseUrl() {
		assertCode(() -> new Environment(project, "dev", "https://gw dev"), Code.BASE_URL_INVALID,
				Map.of("value", "https://gw dev"));
		assertCode(() -> new Environment(project, "dev", "ftp://gw"), Code.BASE_URL_SCHEME, Map.of("value", "ftp://gw"));
		assertCode(() -> new Environment(project, "dev", "http:/gw"), Code.BASE_URL_HOST, Map.of("value", "http:/gw"));
		assertCode(() -> new Environment(project, "dev", "https://gw?x=1"), Code.BASE_URL_QUERY,
				Map.of("value", "https://gw?x=1"));
		assertCode(() -> new Environment(project, "dev", "https://gw#top"), Code.BASE_URL_QUERY,
				Map.of("value", "https://gw#top"));
	}

	@Test
	void namesTheVariableOrSecretThatIsAlreadyUsed() {
		Environment withVariable = new Environment(project, "dev", "https://gw");
		withVariable.replaceVariables(Map.of("token", "plain"));
		assertCode(() -> withVariable.putSecret("token", "cipher"), Code.USED_AS_VARIABLE, Map.of("name", "token"));

		Environment withSecret = new Environment(project, "dev", "https://gw");
		withSecret.putSecret("apiKey", "cipher");
		assertCode(() -> withSecret.replaceVariables(Map.of("apiKey", "plain")), Code.USED_AS_SECRET,
				Map.of("name", "apiKey"));
	}

	@Test
	void namesAnInvalidEmail() {
		assertCode(() -> User.normalizeEmail("Member@Sentinel"), Code.EMAIL_INVALID, Map.of("email", "member@sentinel"));
	}

	private static void assertCode(Executable action, Code code, Map<String, String> params) {
		assertThatThrownBy(action::execute).isInstanceOfSatisfying(InvalidValueException.class, ex -> {
			assertThat(ex.code()).isEqualTo(code);
			assertThat(ex.params()).isEqualTo(params);
		});
	}

}
