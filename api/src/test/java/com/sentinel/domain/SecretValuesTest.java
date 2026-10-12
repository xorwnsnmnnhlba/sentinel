package com.sentinel.domain;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretValuesTest {

	@Test
	void masksLongerSecretsBeforeTheShorterOnesTheyContain() {
		// 짧은 값을 먼저 가리면 긴 값의 나머지(word123)가 드러남. 입력 순서와 관계없이 긴 값부터
		List<String> secrets = List.of("pass", "password123");

		assertThat(SecretValues.mask("token=password123 user=pass", secrets)).isEqualTo("token=**** user=****");
		assertThat(SecretValues.mask(Map.of("Authorization", List.of("Basic password123")), secrets))
			.containsEntry("Authorization", List.of("Basic ****"));
	}

	@Test
	void leavesValuesShorterThanFourCharactersAlone() {
		// 1~3자 값은 관계없는 문자열까지 가릴 수 있어 제외 (예: "id" 값 12가 경로의 12까지 가림)
		assertThat(SecretValues.mask("/api/v12/users/12", List.of("12", "abc"))).isEqualTo("/api/v12/users/12");
	}

}
