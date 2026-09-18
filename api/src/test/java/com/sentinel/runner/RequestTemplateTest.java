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
		RequestTemplate template = new RequestTemplate(HttpMethod.POST, "https://gw/users/{{userId}}",
				Map.of("Authorization", List.of("Bearer {{token}}")), "{\"id\": {{userId}}}");

		ResolvedRequest request = template.resolve(Map.of("userId", "42", "token", "abc"));

		assertThat(request.uri()).isEqualTo(URI.create("https://gw/users/42"));
		assertThat(request.headers().get("Authorization")).containsExactly("Bearer abc");
		assertThat(request.body()).isEqualTo("{\"id\": 42}");
	}

	@Test
	void reportsMissingVariablesFromAllParts() {
		RequestTemplate template = new RequestTemplate(HttpMethod.POST, "https://gw/{{a}}",
				Map.of("X-B", List.of("{{b}}")), "{{c}}");

		assertThatThrownBy(() -> template.resolve(Map.of()))
				.isInstanceOfSatisfying(UnresolvedVariableException.class,
						ex -> assertThat(ex.names()).containsExactlyInAnyOrder("a", "b", "c"));
	}

	@Test
	void rejectsUrlThatIsInvalidAfterResolution() {
		RequestTemplate template = new RequestTemplate(HttpMethod.GET, "https://gw/search?q={{q}}", null, null);

		assertThatThrownBy(() -> template.resolve(Map.of("q", "two words")))
				.isInstanceOf(IllegalArgumentException.class)
				.isNotInstanceOf(UnresolvedVariableException.class);
	}

}
