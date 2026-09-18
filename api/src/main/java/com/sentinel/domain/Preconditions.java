package com.sentinel.domain;

final class Preconditions {

	private Preconditions() {
	}

	static String requireText(String value, String name, int maxLength) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		String trimmed = value.strip();
		if (trimmed.length() > maxLength) {
			throw new IllegalArgumentException(name + " must be at most " + maxLength + " characters");
		}
		return trimmed;
	}

}
