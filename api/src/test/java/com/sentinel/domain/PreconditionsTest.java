package com.sentinel.domain;

import com.sentinel.runner.InvalidValueException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreconditionsTest {

	@Test
	void trimsRequiredText() {
		assertThat(Preconditions.requireText("  billing ", "name", 10)).isEqualTo("billing");
		assertThat(Preconditions.requireText("a".repeat(10), "name", 10)).hasSize(10);
	}

	@Test
	void storesBlankOptionalTextAsNull() {
		assertThat(Preconditions.optionalText(null, "description", 10)).isNull();
		assertThat(Preconditions.optionalText("   ", "description", 10)).isNull();
		assertThat(Preconditions.optionalText(" 결제 API ", "description", 10)).isEqualTo("결제 API");
		assertThatThrownBy(() -> Preconditions.optionalText("a".repeat(11), "description", 10))
			.isInstanceOf(InvalidValueException.class)
			.hasMessage("description must be at most 10 characters");
	}

}
