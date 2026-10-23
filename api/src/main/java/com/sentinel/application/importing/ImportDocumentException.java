package com.sentinel.application.importing;

import java.util.Map;

import com.sentinel.application.InvalidRequestException;

/**
 * 가져올 수 없는 문서. 400 응답에 {@code code}·{@code params}를 함께 담아, 화면이 한국어 문장을 만들고 모르는 code는 영어 메시지 그대로 표시
 * (web/lib/import-message.ts)
 */
public class ImportDocumentException extends InvalidRequestException {

	private final Code code;

	private final Map<String, String> params;

	/** 오류 종류. 괄호 안은 params 이름 */
	public enum Code {
		/** 빈 문서 */
		EMPTY,
		/** 문서 길이 초과 (limit) */
		TOO_LONG,
		/** JSON·YAML 문법 오류 (detail: 파서 메시지) */
		INVALID_SYNTAX,
		/** 값으로 읽을 수 없는 문서 (detail) */
		UNREADABLE,
		/** 최상위가 객체가 아닌 문서 */
		NOT_AN_OBJECT,
		/** Swagger 2.0 문서 (version) */
		SWAGGER_2,
		/** {@code openapi} 항목이 없는 문서. 다른 형식(Postman 등)을 고른 경우가 많음 */
		NOT_OPENAPI,
		/** OpenAPI 3이 아닌 버전 (version) */
		OPENAPI_VERSION,
		/** 경로가 없는 OpenAPI 문서 */
		NO_PATHS,
		/** 지원하는 작업이 없는 OpenAPI 문서 */
		NO_OPERATIONS,
		/** Postman 환경 파일 */
		POSTMAN_ENVIRONMENT,
		/** Postman Collection v1 */
		POSTMAN_V1,
		/** {@code info.schema}가 없는 문서. 다른 형식(OpenAPI 등)을 고른 경우가 많음 */
		NOT_POSTMAN,
		/** 지원하지 않는 Postman 스키마 (schema) */
		POSTMAN_SCHEMA,
		/** 지원하는 요청이 없는 컬렉션 */
		NO_REQUESTS
	}

	private ImportDocumentException(Code code, Map<String, String> params, String message) {
		super(message);
		this.code = code;
		this.params = Map.copyOf(params);
	}

	public Code code() {
		return code;
	}

	public Map<String, String> params() {
		return params;
	}

	static ImportDocumentException empty() {
		return new ImportDocumentException(Code.EMPTY, Map.of(), "document must not be empty");
	}

	static ImportDocumentException tooLong(int limit) {
		return new ImportDocumentException(Code.TOO_LONG, Map.of("limit", String.valueOf(limit)),
				"document must be at most " + limit + " characters");
	}

	static ImportDocumentException invalidSyntax(String detail) {
		return new ImportDocumentException(Code.INVALID_SYNTAX, Map.of("detail", detail),
				"document is not valid JSON or YAML: " + detail);
	}

	static ImportDocumentException unreadable(String detail) {
		return new ImportDocumentException(Code.UNREADABLE, Map.of("detail", detail),
				"document could not be read: " + detail);
	}

	static ImportDocumentException notAnObject() {
		return new ImportDocumentException(Code.NOT_AN_OBJECT, Map.of(), "document must be a JSON or YAML object");
	}

	static ImportDocumentException swagger2(String version) {
		return new ImportDocumentException(Code.SWAGGER_2, Map.of("version", version),
				"only OpenAPI 3 documents are supported (found swagger " + version + ")");
	}

	static ImportDocumentException notOpenApi() {
		return new ImportDocumentException(Code.NOT_OPENAPI, Map.of(),
				"only OpenAPI 3 documents are supported (found openapi none)");
	}

	static ImportDocumentException openApiVersion(String version) {
		return new ImportDocumentException(Code.OPENAPI_VERSION, Map.of("version", version),
				"only OpenAPI 3 documents are supported (found openapi " + version + ")");
	}

	static ImportDocumentException noPaths() {
		return new ImportDocumentException(Code.NO_PATHS, Map.of(), "document defines no paths");
	}

	static ImportDocumentException noOperations() {
		return new ImportDocumentException(Code.NO_OPERATIONS, Map.of(), "document defines no supported operations");
	}

	static ImportDocumentException postmanEnvironment() {
		return new ImportDocumentException(Code.POSTMAN_ENVIRONMENT, Map.of(),
				"this is a Postman environment, not a collection");
	}

	static ImportDocumentException postmanV1() {
		return new ImportDocumentException(Code.POSTMAN_V1, Map.of(),
				"only Postman Collection v2.0 and v2.1 are supported (found v1); export the collection again");
	}

	static ImportDocumentException notPostman() {
		return new ImportDocumentException(Code.NOT_POSTMAN, Map.of(),
				"document is not a Postman collection (info.schema is missing)");
	}

	static ImportDocumentException postmanSchema(String schema) {
		return new ImportDocumentException(Code.POSTMAN_SCHEMA, Map.of("schema", schema),
				"only Postman Collection v2.0 and v2.1 are supported (found schema " + schema + ")");
	}

	static ImportDocumentException noRequests() {
		return new ImportDocumentException(Code.NO_REQUESTS, Map.of(), "collection defines no supported requests");
	}

}
