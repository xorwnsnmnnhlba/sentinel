package com.sentinel.domain;

import java.util.Map;

import com.sentinel.runner.InvalidValueException;
import com.sentinel.runner.InvalidValueException.Code;

final class Preconditions {

	private Preconditions() {
	}

	static String requireText(String value, String name, int maxLength) {
		if (value == null || value.isBlank()) {
			throw new InvalidValueException(Code.REQUIRED, Map.of("target", name), name + " must not be blank", null);
		}
		String trimmed = value.strip();
		if (trimmed.length() > maxLength) {
			throw new InvalidValueException(Code.TOO_LONG, Map.of("target", name, "max", String.valueOf(maxLength)),
					name + " must be at most " + maxLength + " characters", null);
		}
		return trimmed;
	}

	/**
	 * 선택 입력. 공백뿐이면 null로 저장
	 */
	static String optionalText(String value, String name, int maxLength) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return requireText(value, name, maxLength);
	}

}
