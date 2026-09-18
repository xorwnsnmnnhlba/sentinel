package com.sentinel.runner.assertion;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.sentinel.runner.HttpExchange;

/**
 * 응답 판정 규칙. {@code type} 속성으로 종류를 구분하는 JSON으로 저장.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({ @JsonSubTypes.Type(value = StatusCodeAssertion.class, name = "statusCode"),
		@JsonSubTypes.Type(value = ResponseTimeAssertion.class, name = "responseTime"),
		@JsonSubTypes.Type(value = HeaderAssertion.class, name = "header"),
		@JsonSubTypes.Type(value = JsonPathAssertion.class, name = "jsonPath"),
		@JsonSubTypes.Type(value = BodyContainsAssertion.class, name = "bodyContains") })
public sealed interface Assertion
		permits StatusCodeAssertion, ResponseTimeAssertion, HeaderAssertion, JsonPathAssertion, BodyContainsAssertion {

	AssertionResult evaluate(HttpExchange exchange);

	/** 알림·결과 화면용 규칙 설명. 예: {@code status in [2xx]} */
	String describe();

}
