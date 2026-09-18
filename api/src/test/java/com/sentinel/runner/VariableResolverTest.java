package com.sentinel.runner;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VariableResolverTest {

	private final VariableResolver resolver = new VariableResolver(
			Map.of("userId", "42", "api.version", "v1", "token", "a$b\\c", "nested", "{{userId}}"));

	private final Set<String> missing = new LinkedHashSet<>();

	@Test
	void replacesVariablesIncludingDotsAndWhitespaceInBraces() {
		assertThat(resolver.resolve("/{{api.version}}/users/{{ userId }}", missing)).isEqualTo("/v1/users/42");
		assertThat(missing).isEmpty();
	}

	@Test
	void insertsValuesLiterallyWithoutRegexSpecialMeaning() {
		assertThat(resolver.resolve("Bearer {{token}}", missing)).isEqualTo("Bearer a$b\\c");
	}

	@Test
	void doesNotResolveValuesRecursively() {
		assertThat(resolver.resolve("{{nested}}", missing)).isEqualTo("{{userId}}");
		assertThat(missing).isEmpty();
	}

	@Test
	void collectsAllMissingVariablesAndKeepsPlaceholders() {
		assertThat(resolver.resolve("{{a}}/{{userId}}/{{b}}/{{a}}", missing)).isEqualTo("{{a}}/42/{{b}}/{{a}}");
		assertThat(missing).containsExactly("a", "b");
	}

	@Test
	void treatsBackslashEscapedBracesAsLiteral() {
		assertThat(resolver.resolve("\\{{userId}} = {{userId}}", missing)).isEqualTo("{{userId}} = 42");
		assertThat(missing).isEmpty();
	}

	@Test
	void leavesNonVariableBracesUntouched() {
		assertThat(resolver.resolve("{\"a\":{\"b\":1}} {{ }} {{bad name}}", missing))
				.isEqualTo("{\"a\":{\"b\":1}} {{ }} {{bad name}}");
		assertThat(missing).isEmpty();
	}

	@Test
	void returnsNullForNullTemplate() {
		assertThat(resolver.resolve(null, missing)).isNull();
	}

}
