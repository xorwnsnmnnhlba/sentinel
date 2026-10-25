package com.sentinel.domain;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentTest {

	private final Project project = new Project("billing", null);

	@Test
	void stripsTrailingSlashesFromBaseUrl() {
		assertThat(new Environment(project, "dev", "https://gw.dev.internal/api//").getBaseUrl())
				.isEqualTo("https://gw.dev.internal/api");
	}

	@ParameterizedTest
	@ValueSource(strings = { "gw.dev.internal", "/api", "ftp://gw.dev.internal", "https://gw.dev.internal?x=1",
			"https://gw.dev.internal#top", "http://", "https://gw dev" })
	void rejectsInvalidBaseUrl(String baseUrl) {
		assertThatThrownBy(() -> new Environment(project, "dev", baseUrl)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsBlankName() {
		assertThatThrownBy(() -> new Environment(project, " ", "https://gw.dev.internal"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void exposesVariablesReadOnly() {
		Environment environment = new Environment(project, "dev", "https://gw.dev.internal");
		environment.putVariable("userId", "1");

		assertThat(environment.getVariables()).containsEntry("userId", "1");
		// 직접 작성한 방어적 getter. Lombok @Getter로 바뀌면 내부 Map이 노출되어 실패
		assertThatThrownBy(() -> environment.getVariables().put("x", "y"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void replacesAllVariablesAndKeepsPreviousOnInvalidInput() {
		Environment environment = new Environment(project, "dev", "https://gw.dev.internal");
		environment.putVariable("userId", "1");

		environment.replaceVariables(Map.of("token", "t"));
		assertThat(environment.getVariables()).containsExactly(Map.entry("token", "t"));

		// 앞 항목은 들어간 뒤 실패해도 바꾸기 전 변수만 남음
		Map<String, String> invalid = new LinkedHashMap<>();
		invalid.put("fresh", "1");
		invalid.put("userId", null);
		assertThatThrownBy(() -> environment.replaceVariables(invalid)).isInstanceOf(IllegalArgumentException.class);
		assertThat(environment.getVariables()).containsExactly(Map.entry("token", "t"));

		environment.replaceVariables(Map.of());
		assertThat(environment.getVariables()).isEmpty();
	}

}
