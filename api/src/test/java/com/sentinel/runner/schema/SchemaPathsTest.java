package com.sentinel.runner.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaPathsTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "$.a|$", "$.a.b|$.a", "$.items[]|$.items", "$.items[].id|$.items[]",
			"$[][]|$[]", "$.*|$", "$.*.n|$.*", "$['a.b']|$", "$['a.b'].c|$['a.b']", "$['it\\'s'].x|$['it\\'s']",
			"$['a]b'].c|$['a]b']" })
	void findsTheParent(String path, String parent) {
		assertThat(SchemaPaths.parent(path)).isEqualTo(parent);
	}

	@Test
	void rootHasNoParent() {
		assertThat(SchemaPaths.parent("$")).isNull();
		assertThat(SchemaPaths.lastSegment("$")).isEmpty();
	}

	@Test
	void knowsWhichSegmentsMayBeEmpty() {
		assertThat(SchemaPaths.mayBeEmpty("$.items[]")).isTrue();
		assertThat(SchemaPaths.mayBeEmpty("$.byId.*")).isTrue();
		assertThat(SchemaPaths.mayBeEmpty("$.items")).isFalse();
		assertThat(SchemaPaths.mayBeEmpty("$['*']")).isFalse();
	}

	@Test
	void requiresAnArrayForElementsAndAnObjectForKeys() {
		assertThat(SchemaPaths.containerOf("$.items[]")).isEqualTo("array");
		assertThat(SchemaPaths.containerOf("$.items[].id")).isEqualTo("object");
		assertThat(SchemaPaths.containerOf("$['a.b']")).isEqualTo("object");
	}

	@Test
	void detectsAncestors() {
		assertThat(SchemaPaths.isAncestor("$.a", "$.a.b.c")).isTrue();
		assertThat(SchemaPaths.isAncestor("$.a", "$.a")).isFalse();
		// 이름이 겹쳐도 구획이 다르면 상위가 아님
		assertThat(SchemaPaths.isAncestor("$.a", "$.ab")).isFalse();
	}

}
