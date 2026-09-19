package com.sentinel.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sentinel.runner.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestSpecTest {

	@Test
	void requiresPathStartingWithSlash() {
		assertThatThrownBy(() -> new RequestSpec(HttpMethod.GET, "users/1", Map.of(), null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RequestSpec(HttpMethod.GET, "https://gw/users", Map.of(), null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void allowsVariablesAndQueryInPath() {
		assertThat(new RequestSpec(HttpMethod.GET, "/users/{{userId}}?page=1", null, null).getPath())
				.isEqualTo("/users/{{userId}}?page=1");
	}

	@Test
	void limitsBodyLengthToColumnSize() {
		String max = "a".repeat(RequestSpec.MAX_BODY_LENGTH);

		assertThat(new RequestSpec(HttpMethod.POST, "/a", null, max).getBody()).hasSize(RequestSpec.MAX_BODY_LENGTH);
		assertThatThrownBy(() -> new RequestSpec(HttpMethod.POST, "/a", null, max + "a"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void copiesHeadersDefensively() {
		List<String> values = new ArrayList<>(List.of("Bearer {{token}}"));
		RequestSpec spec = new RequestSpec(HttpMethod.GET, "/users", Map.of("Authorization", values), null);
		values.add("changed");

		assertThat(spec.getHeaders()).containsExactly(Map.entry("Authorization", List.of("Bearer {{token}}")));
		assertThatThrownBy(() -> spec.getHeaders().get("Authorization").add("x"))
				.isInstanceOf(UnsupportedOperationException.class);
		// 직접 작성한 방어적 getter. Lombok @Getter로 바뀌면 내부 Map이 노출되어 실패
		assertThatThrownBy(() -> spec.getHeaders().put("X-Added", List.of("x")))
				.isInstanceOf(UnsupportedOperationException.class);
	}

}
