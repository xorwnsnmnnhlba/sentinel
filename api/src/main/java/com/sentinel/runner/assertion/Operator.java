package com.sentinel.runner.assertion;

public enum Operator {

	EXISTS("exists"), NOT_EXISTS("does not exist"), EQUALS("=="), NOT_EQUALS("!="), CONTAINS("contains"),
	MATCHES("matches"), GREATER_THAN(">"), GREATER_THAN_OR_EQUAL(">="), LESS_THAN("<"), LESS_THAN_OR_EQUAL("<=");

	private final String symbol;

	Operator(String symbol) {
		this.symbol = symbol;
	}

	public String symbol() {
		return symbol;
	}

	boolean needsValue() {
		return this != EXISTS && this != NOT_EXISTS;
	}

	boolean isNumericComparison() {
		return this == GREATER_THAN || this == GREATER_THAN_OR_EQUAL || this == LESS_THAN
				|| this == LESS_THAN_OR_EQUAL;
	}

}
