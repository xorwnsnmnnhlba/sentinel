package com.sentinel.domain;

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
		assertThatThrownBy(() -> environment.getVariables().put("x", "y"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

}
