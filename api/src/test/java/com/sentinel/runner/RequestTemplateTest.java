package com.sentinel.runner;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestTemplateTest {

	@Test
	void resolvesUrlHeaderValuesAndBody() {
		RequestTemplate template = new RequestTemplate(HttpMethod.POST, "https://gw", "/users/{{userId}}",
				Map.of("Authorization", List.of("Bearer {{token}}")), "{\"id\": {{userId}}}");

		ResolvedRequest request = template.resolve(Map.of("userId", "42", "token", "abc"));

		assertThat(request.uri()).isEqualTo(URI.create("https://gw/users/42"));
		assertThat(request.headers().get("Authorization")).containsExactly("Bearer abc");
		assertThat(request.body()).isEqualTo("{\"id\": 42}");
	}

	@Test
	void listsVariablesOfPathHeaderValuesAndBodyInOrder() {
		// 헤더명·이스케이프한 {{는 치환 대상이 아니므로 제외
		RequestTemplate template = new RequestTemplate(HttpMethod.POST, "https://gw", "/{{a}}/items",
				Map.of("X-{{name}}", List.of("{{b}}", "{{ c }}")), "{\"d\": \"{{d}}\", \"e\": \"\\{{e}}\", \"a\": \"{{a}}\"}");

		assertThat(template.variableNames()).containsExactly("a", "b", "c", "d");
	}

	@Test
	void reportsMissingVariablesFromAllParts() {
		RequestTemplate template = new RequestTemplate(HttpMethod.POST, "https://gw", "/{{a}}",
				Map.of("X-B", List.of("{{b}}")), "{{c}}");

		assertThatThrownBy(() -> template.resolve(Map.of()))
				.isInstanceOfSatisfying(UnresolvedVariableException.class,
						ex -> assertThat(ex.names()).containsExactlyInAnyOrder("a", "b", "c"));
	}

	@Test
	void rejectsUrlThatIsInvalidAfterResolution() {
		RequestTemplate template = new RequestTemplate(HttpMethod.GET, "https://gw", "/search?q={{q}}", null, null);

		assertThatThrownBy(() -> template.resolve(Map.of("q", "two words")))
				.isInstanceOf(IllegalArgumentException.class)
				.isNotInstanceOf(UnresolvedVariableException.class);
	}


	@Test
	void resolvesPathStartingWithVariable() {
		RequestTemplate template = new RequestTemplate(HttpMethod.GET, "https://gw", "{{prefix}}/users", null, null);

		assertThat(template.resolve(Map.of("prefix", "/billing/v1")).uri())
			.isEqualTo(URI.create("https://gw/billing/v1/users"));
	}

	@Test
	void rejectsPathThatDoesNotStartWithSlashAfterResolution() {
		RequestTemplate template = new RequestTemplate(HttpMethod.GET, "https://gw", "{{prefix}}/users", null, null);

		// "https://gw" + "evil.example/users"처럼 다른 호스트로 전송되지 않도록 치환 후 경로 확인
		assertThatThrownBy(() -> template.resolve(Map.of("prefix", ".evil.example")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must start with '/' after variable substitution");
	}

}
