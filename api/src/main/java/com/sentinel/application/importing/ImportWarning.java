package com.sentinel.application.importing;

import java.util.HashMap;
import java.util.Map;

/**
 * 가져오기 경고·안내. 화면은 {@code code}·{@code params}로 한국어 문장을 만들고, 모르는 {@code code}는 {@code message} 그대로 표시
 * (web/lib/import-warning.ts)
 *
 * @param params 문장에 넣을 값. 이름은 code마다 고정 ({@link Code} 설명)
 * @param message 영어 문장. API 직접 사용·이전 화면용
 */
public record ImportWarning(Code code, Map<String, String> params, String message) {

	public ImportWarning {
		params = Map.copyOf(params);
	}

	/** 경고 종류. 괄호 안은 params 이름 */
	public enum Code {
		/** 변수 이름에 쓸 수 없는 문자를 {@code _}로 바꿈 (name, variable) */
		VARIABLE_RENAMED,
		/** 지원하지 않는 메서드라 제외 (source, method) */
		METHOD_SKIPPED,
		/** 사용 중단 표시된 작업 */
		DEPRECATED,
		/** 필수 쿠키 파라미터 미지원 (name) */
		COOKIE_PARAMETER,
		/** 문서에 정의가 없는 보안 스킴 (scheme) */
		SECURITY_SCHEME_UNDEFINED,
		/** 지원하지 않는 보안 스킴 (scheme, kind. 예: {@code http digest}) */
		SECURITY_SCHEME_UNSUPPORTED,
		/** 보안 스킴을 요청에 넣었으니 시크릿 등록 필요 (scheme, sentAs, variable) */
		SECURITY_SCHEME_SECRET,
		/** 토큰 보안 스킴. 시크릿 등록 또는 앞 케이스의 값 추출 (scheme, sentAs, variable) */
		SECURITY_SCHEME_TOKEN,
		/** JSON이 아닌 요청 본문이라 비움 (mediaTypes) */
		BODY_MEDIA_TYPE_UNSUPPORTED,
		/** 필수 요청 본문인데 예시·스키마가 없어 비움 */
		BODY_MISSING,
		/** 스키마로 만든 요청 본문 */
		BODY_FROM_SCHEMA,
		/** 요청 본문 길이 초과로 비움 (limit) */
		BODY_TOO_LONG,
		/** 외부 예시 미지원 (ref) */
		EXTERNAL_EXAMPLE,
		/** 선언된 2xx 응답이 없어 2xx 전체로 판정 */
		NO_SUCCESS_RESPONSE,
		/** 순환·깊이 초과로 참조 해석 실패 (location, ref) */
		REFERENCE_UNRESOLVED,
		/** 외부 참조 미지원 (location, ref) */
		REFERENCE_EXTERNAL,
		/** 참조 대상 없음 (location, ref) */
		REFERENCE_NOT_FOUND,
		/** GraphQL 변수가 JSON이 아니라 제외 */
		GRAPHQL_VARIABLES_INVALID,
		/** 지원하지 않는 Postman 본문 형식이라 비움 (mode) */
		BODY_MODE_UNSUPPORTED,
		/** Basic 인증. base64 값을 시크릿으로 등록 필요 (variable) */
		BASIC_AUTH_SECRET,
		/** 키 이름이 없는 API 키 인증이라 제외 */
		API_KEY_NAME_MISSING,
		/** 지원하지 않는 Postman 인증 (type) */
		AUTH_UNSUPPORTED,
		/** 평문 자격증명을 변수로 바꿈 (credential, variable. credential이 header면 header) */
		CREDENTIAL_REPLACED,
		/** 자격증명을 시크릿으로 등록 필요 (credential, variable. credential이 header면 header) */
		CREDENTIAL_SECRET,
		/** 토큰 자격증명. 시크릿 등록 또는 앞 케이스의 값 추출 (credential, variable) */
		CREDENTIAL_TOKEN,
		/** Postman 스크립트 미지원 (script. 예: {@code prerequest}, {@code test}) */
		SCRIPT_UNSUPPORTED,
		/** 컬렉션 변수는 가져오지 않음 (names) */
		COLLECTION_VARIABLES
	}

	/** 자격증명 종류 (CREDENTIAL_* 의 credential 값) */
	enum Credential {
		/** {@code bearerToken} */
		BEARER_TOKEN("bearerToken", "bearer token"),
		/** {@code accessToken} */
		ACCESS_TOKEN("accessToken", "OAuth 2.0 access token"),
		/** {@code apiKey} */
		API_KEY("apiKey", "API key"),
		/** {@code header}. 헤더 이름은 params의 header */
		HEADER("header", "header value");

		private final String param;

		private final String text;

		Credential(String param, String text) {
			this.param = param;
			this.text = text;
		}

	}

	static ImportWarning variableRenamed(String name, String variable) {
		return new ImportWarning(Code.VARIABLE_RENAMED, Map.of("name", name, "variable", variable),
				"parameter '" + name + "' is used as variable " + variable);
	}

	static ImportWarning methodSkipped(String source, String method) {
		return new ImportWarning(Code.METHOD_SKIPPED, Map.of("source", source, "method", method),
				source + ": " + ("TRACE".equals(method) ? "TRACE" : "method " + method)
						+ " is not supported and was skipped");
	}

	static ImportWarning deprecated() {
		return new ImportWarning(Code.DEPRECATED, Map.of(), "operation is deprecated");
	}

	static ImportWarning cookieParameter(String name) {
		return new ImportWarning(Code.COOKIE_PARAMETER, Map.of("name", name),
				"required cookie parameter '" + name + "' is not supported");
	}

	static ImportWarning securitySchemeUndefined(String scheme) {
		return new ImportWarning(Code.SECURITY_SCHEME_UNDEFINED, Map.of("scheme", scheme),
				"security scheme '" + scheme + "' is not defined");
	}

	static ImportWarning securitySchemeUnsupported(String scheme, String kind) {
		return new ImportWarning(Code.SECURITY_SCHEME_UNSUPPORTED, Map.of("scheme", scheme, "kind", kind),
				"security scheme '" + scheme + "' (" + kind + ") is not supported");
	}

	/**
	 * @param sentAs 요청에 넣은 모양. 예: {@code 'Authorization: Bearer {{token}}'}
	 * @param token 로그인으로 발급받는 토큰. 앞 케이스의 값 추출로 받는 방법도 안내
	 */
	static ImportWarning securityScheme(String scheme, String sentAs, String variable, boolean token) {
		return new ImportWarning(token ? Code.SECURITY_SCHEME_TOKEN : Code.SECURITY_SCHEME_SECRET,
				Map.of("scheme", scheme, "sentAs", sentAs, "variable", variable),
				"security scheme '" + scheme + "' is sent as " + sentAs + "; add " + variable
						+ " as a secret of the environment" + (token ? loginTokenHint(variable) : ""));
	}

	static ImportWarning bodyMediaTypeUnsupported(String mediaTypes) {
		return new ImportWarning(Code.BODY_MEDIA_TYPE_UNSUPPORTED, Map.of("mediaTypes", mediaTypes),
				"request body media type [" + mediaTypes + "] is not supported; body left empty");
	}

	static ImportWarning bodyMissing() {
		return new ImportWarning(Code.BODY_MISSING, Map.of(),
				"request body has no JSON example or schema; body left empty");
	}

	static ImportWarning bodyFromSchema() {
		return new ImportWarning(Code.BODY_FROM_SCHEMA, Map.of(),
				"request body was generated from the schema; replace the placeholder values");
	}

	/**
	 * @param example 문서의 예시 본문 (OpenAPI). Postman은 요청 본문 그대로라 false
	 */
	static ImportWarning bodyTooLong(int limit, boolean example) {
		return new ImportWarning(Code.BODY_TOO_LONG, Map.of("limit", String.valueOf(limit)),
				"request body " + (example ? "example " : "") + "exceeds " + limit + " characters; body left empty");
	}

	static ImportWarning externalExample(String ref) {
		return new ImportWarning(Code.EXTERNAL_EXAMPLE, Map.of("ref", ref),
				"external example '" + ref + "' is not supported");
	}

	static ImportWarning noSuccessResponse() {
		return new ImportWarning(Code.NO_SUCCESS_RESPONSE, Map.of(), "no 2xx response is declared; expecting any 2xx");
	}

	/**
	 * @param location 참조가 있던 곳. 경로(예: {@code /pets}) 또는 {@code parameter}, {@code security scheme},
	 * {@code request body}, {@code example}, {@code schema}
	 * @param ref 참조. 문자열이 아니면 {@code null}
	 */
	static ImportWarning referenceUnresolved(String location, String ref) {
		return new ImportWarning(Code.REFERENCE_UNRESOLVED, Map.of("location", location, "ref", String.valueOf(ref)),
				location + ": reference '" + ref + "' could not be resolved");
	}

	static ImportWarning referenceExternal(String location, String ref) {
		return new ImportWarning(Code.REFERENCE_EXTERNAL, Map.of("location", location, "ref", ref),
				location + ": external reference '" + ref + "' is not supported");
	}

	static ImportWarning referenceNotFound(String location, String ref) {
		return new ImportWarning(Code.REFERENCE_NOT_FOUND, Map.of("location", location, "ref", ref),
				location + ": reference '" + ref + "' was not found");
	}

	static ImportWarning graphqlVariablesInvalid() {
		return new ImportWarning(Code.GRAPHQL_VARIABLES_INVALID, Map.of(),
				"GraphQL variables are not valid JSON and were left out");
	}

	static ImportWarning bodyModeUnsupported(String mode) {
		return new ImportWarning(Code.BODY_MODE_UNSUPPORTED, Map.of("mode", mode),
				"request body mode '" + mode + "' is not supported; body left empty");
	}

	static ImportWarning basicAuthSecret(String variable) {
		return new ImportWarning(Code.BASIC_AUTH_SECRET, Map.of("variable", variable),
				"basic auth is sent as 'Authorization: Basic " + variable + "'; add " + variable
						+ " as a secret holding base64 of 'username:password'");
	}

	static ImportWarning apiKeyNameMissing() {
		return new ImportWarning(Code.API_KEY_NAME_MISSING, Map.of(), "API key auth has no key name and was left out");
	}

	static ImportWarning authUnsupported(String type) {
		return new ImportWarning(Code.AUTH_UNSUPPORTED, Map.of("type", type),
				"auth type '" + type + "' is not supported");
	}

	/**
	 * @param header credential이 {@link Credential#HEADER}일 때 헤더 이름, 아니면 null
	 */
	static ImportWarning credentialReplaced(Credential credential, String header, String variable) {
		return new ImportWarning(Code.CREDENTIAL_REPLACED, credentialParams(credential, header, variable),
				"literal " + credentialText(credential, header) + " was replaced with " + variable);
	}

	/**
	 * 토큰(bearer·OAuth 2.0)은 로그인으로 발급받는 경우가 많아 앞 케이스의 값 추출로 받는 방법도 안내
	 * @param header credential이 {@link Credential#HEADER}일 때 헤더 이름, 아니면 null
	 */
	static ImportWarning credentialSecret(Credential credential, String header, String variable) {
		boolean token = credential == Credential.BEARER_TOKEN || credential == Credential.ACCESS_TOKEN;
		return new ImportWarning(token ? Code.CREDENTIAL_TOKEN : Code.CREDENTIAL_SECRET,
				credentialParams(credential, header, variable),
				"add " + variable + " as a secret of the environment (" + credentialText(credential, header) + ")"
						+ (token ? loginTokenHint(variable) : ""));
	}

	/**
	 * @param script Postman 이벤트 이름. 예: {@code prerequest}, {@code test}
	 */
	static ImportWarning scriptUnsupported(String script) {
		return new ImportWarning(Code.SCRIPT_UNSUPPORTED, Map.of("script", script),
				(script.equals("prerequest") ? "pre-request" : script)
						+ " script is not supported; add assertions instead");
	}

	/**
	 * @param names 변수 이름. 값은 자격증명일 수 있어 넣지 않음
	 */
	static ImportWarning collectionVariables(String names) {
		return new ImportWarning(Code.COLLECTION_VARIABLES, Map.of("names", names),
				"collection variables are not imported; define them in the environment: " + names);
	}

	/**
	 * 로그인 API가 발급하는 토큰(만료됨)은 고정 시크릿 대신 앞 케이스의 값 추출로 받는 방법도 안내.
	 * 시크릿에 넣은 토큰은 만료되면 그때부터 점검이 실패함
	 */
	private static String loginTokenHint(String variable) {
		return ", or if a login request issues it, extract it into " + variable + " in an earlier case";
	}

	private static Map<String, String> credentialParams(Credential credential, String header, String variable) {
		Map<String, String> params = new HashMap<>();
		params.put("credential", credential.param);
		params.put("variable", variable);
		if (credential == Credential.HEADER) {
			params.put("header", header);
		}
		return params;
	}

	private static String credentialText(Credential credential, String header) {
		return credential == Credential.HEADER ? "'" + header + "' " + credential.text : credential.text;
	}

}
