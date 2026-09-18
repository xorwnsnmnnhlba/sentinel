package com.sentinel.runner;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolvedRequestTest {

	@Test
	void rejectsMissingMethodOrUri() {
		assertThatThrownBy(() -> new ResolvedRequest(null, URI.create("http://gw/a"), Map.of(), null))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ResolvedRequest(HttpMethod.GET, null, Map.of(), null))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void rejectsRelativeUri() {
		assertThatThrownBy(() -> ResolvedRequest.of(HttpMethod.GET, "/users/1"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void treatsNullHeadersAsEmpty() {
		assertThat(new ResolvedRequest(HttpMethod.GET, URI.create("http://gw/a"), null, null).headers()).isEmpty();
	}

	@Test
	void looksUpHeadersCaseInsensitivelyAndMergesDuplicateNames() {
		Map<String, List<String>> headers = new HashMap<>();
		headers.put("Accept", List.of("application/json"));
		headers.put("accept", List.of("text/plain"));

		ResolvedRequest request = new ResolvedRequest(HttpMethod.GET, URI.create("http://gw/a"), headers, null);

		assertThat(request.headers()).hasSize(1);
		assertThat(request.headers().get("ACCEPT")).containsExactlyInAnyOrder("application/json", "text/plain");
	}

	@Test
	void copiesHeadersDefensively() {
		List<String> values = new ArrayList<>(List.of("a"));
		Map<String, List<String>> headers = new HashMap<>(Map.of("X-Test", values));

		ResolvedRequest request = new ResolvedRequest(HttpMethod.GET, URI.create("http://gw/a"), headers, null);
		values.add("b");
		headers.put("X-Other", List.of("c"));

		assertThat(request.headers()).containsExactly(Map.entry("X-Test", List.of("a")));
		assertThatThrownBy(() -> request.headers().put("X-New", List.of())).isInstanceOf(UnsupportedOperationException.class);
	}

}
