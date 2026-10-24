package com.sentinel.application.importing;

import java.util.List;
import java.util.Map;

import com.sentinel.application.importing.ImportDocumentException.Code;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 화면이 한국어 문장을 만드는 code·params (web/lib/import-message.ts). 영어 메시지는 변환기 테스트에서 확인
 */
class ImportDocumentExceptionTest {

	@Test
	void namesEachRejectedDocumentWithTheValuesTheScreenNeeds() {
		assertThat(List.of(ImportDocumentException.empty(), ImportDocumentException.tooLong(5_242_880),
				ImportDocumentException.invalidSyntax("line 1"), ImportDocumentException.unreadable("too deep"),
				ImportDocumentException.notAnObject(), ImportDocumentException.swagger2("2.0"),
				ImportDocumentException.notOpenApi(), ImportDocumentException.openApiVersion("2.0"),
				ImportDocumentException.noPaths(), ImportDocumentException.noOperations(),
				ImportDocumentException.postmanEnvironment(), ImportDocumentException.postmanV1(),
				ImportDocumentException.notPostman(), ImportDocumentException.postmanSchema("v3"),
				ImportDocumentException.noRequests()))
			.extracting(ImportDocumentException::code, ImportDocumentException::params)
			.containsExactly(rejected(Code.EMPTY, Map.of()), rejected(Code.TOO_LONG, Map.of("limit", "5242880")),
					rejected(Code.INVALID_SYNTAX, Map.of("detail", "line 1")),
					rejected(Code.UNREADABLE, Map.of("detail", "too deep")), rejected(Code.NOT_AN_OBJECT, Map.of()),
					rejected(Code.SWAGGER_2, Map.of("version", "2.0")), rejected(Code.NOT_OPENAPI, Map.of()),
					rejected(Code.OPENAPI_VERSION, Map.of("version", "2.0")), rejected(Code.NO_PATHS, Map.of()),
					rejected(Code.NO_OPERATIONS, Map.of()), rejected(Code.POSTMAN_ENVIRONMENT, Map.of()),
					rejected(Code.POSTMAN_V1, Map.of()), rejected(Code.NOT_POSTMAN, Map.of()),
					rejected(Code.POSTMAN_SCHEMA, Map.of("schema", "v3")), rejected(Code.NO_REQUESTS, Map.of()));
	}

	@Test
	void tellsAMissingVersionFromAnOldOne() {
		OpenApiConverter converter = new OpenApiConverter();

		assertThatThrownBy(() -> converter.convert("info: { title: x }")).isInstanceOf(ImportDocumentException.class)
			.extracting("code")
			.isEqualTo("NOT_OPENAPI");
		assertThatThrownBy(() -> converter.convert("openapi: 2.0")).isInstanceOf(ImportDocumentException.class)
			.extracting("code")
			.isEqualTo("OPENAPI_VERSION");
	}

	private static Tuple rejected(Code code, Map<String, String> params) {
		return Tuple.tuple(code.name(), params);
	}

}
