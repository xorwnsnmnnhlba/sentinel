package com.sentinel.application.importing;

import java.util.List;
import java.util.Map;

import com.sentinel.application.importing.ImportWarning.Code;
import com.sentinel.application.importing.ImportWarning.Credential;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화면이 한국어 문장을 만드는 code·params (web/lib/import-message.ts). 영어 문장은 변환기 테스트에서 확인
 */
class ImportWarningTest {

	@Test
	void namesEachWarningWithTheValuesTheScreenNeeds() {
		assertThat(List.of(ImportWarning.variableRenamed("store[id]", "{{store_id_}}"),
				ImportWarning.methodSkipped("TRACE /pets", "TRACE"), ImportWarning.deprecated(),
				ImportWarning.cookieParameter("session"), ImportWarning.securitySchemeUndefined("ghost"),
				ImportWarning.securitySchemeUnsupported("digestAuth", "http digest"),
				ImportWarning.bodyMediaTypeUnsupported("multipart/form-data, text/plain"), ImportWarning.bodyMissing(),
				ImportWarning.bodyFromSchema(), ImportWarning.bodyTooLong(1000, true),
				ImportWarning.externalExample("https://example.com/order.json"), ImportWarning.noSuccessResponse(),
				ImportWarning.referenceUnresolved("request body", null),
				ImportWarning.referenceExternal("parameter", "common.yaml#/x"),
				ImportWarning.referenceNotFound("/missing", "#/gone"), ImportWarning.graphqlVariablesInvalid(),
				ImportWarning.bodyModeUnsupported("formdata"), ImportWarning.basicAuthSecret("{{basicAuth}}"),
				ImportWarning.apiKeyNameMissing(), ImportWarning.authUnsupported("hawk"),
				ImportWarning.scriptUnsupported("prerequest"), ImportWarning.collectionVariables("baseUrl, token")))
			.extracting(ImportWarning::code, ImportWarning::params)
			.containsExactly(warning(Code.VARIABLE_RENAMED, Map.of("name", "store[id]", "variable", "{{store_id_}}")),
					warning(Code.METHOD_SKIPPED, Map.of("source", "TRACE /pets", "method", "TRACE")),
					warning(Code.DEPRECATED, Map.of()), warning(Code.COOKIE_PARAMETER, Map.of("name", "session")),
					warning(Code.SECURITY_SCHEME_UNDEFINED, Map.of("scheme", "ghost")),
					warning(Code.SECURITY_SCHEME_UNSUPPORTED, Map.of("scheme", "digestAuth", "kind", "http digest")),
					warning(Code.BODY_MEDIA_TYPE_UNSUPPORTED, Map.of("mediaTypes", "multipart/form-data, text/plain")),
					warning(Code.BODY_MISSING, Map.of()), warning(Code.BODY_FROM_SCHEMA, Map.of()),
					warning(Code.BODY_TOO_LONG, Map.of("limit", "1000")),
					warning(Code.EXTERNAL_EXAMPLE, Map.of("ref", "https://example.com/order.json")),
					warning(Code.NO_SUCCESS_RESPONSE, Map.of()),
					warning(Code.REFERENCE_UNRESOLVED, Map.of("location", "request body", "ref", "null")),
					warning(Code.REFERENCE_EXTERNAL, Map.of("location", "parameter", "ref", "common.yaml#/x")),
					warning(Code.REFERENCE_NOT_FOUND, Map.of("location", "/missing", "ref", "#/gone")),
					warning(Code.GRAPHQL_VARIABLES_INVALID, Map.of()),
					warning(Code.BODY_MODE_UNSUPPORTED, Map.of("mode", "formdata")),
					warning(Code.BASIC_AUTH_SECRET, Map.of("variable", "{{basicAuth}}")),
					warning(Code.API_KEY_NAME_MISSING, Map.of()), warning(Code.AUTH_UNSUPPORTED, Map.of("type", "hawk")),
					warning(Code.SCRIPT_UNSUPPORTED, Map.of("script", "prerequest")),
					warning(Code.COLLECTION_VARIABLES, Map.of("names", "baseUrl, token")));
	}

	@Test
	void separatesTokensThatALoginCaseCanIssue() {
		assertThat(List.of(ImportWarning.securityScheme("bearerAuth", "'Authorization: Bearer {{bearerAuth}}'",
				"{{bearerAuth}}", true),
				ImportWarning.securityScheme("apiKeyHeader", "'X-API-Key: {{apiKeyHeader}}'", "{{apiKeyHeader}}", false)))
			.extracting(ImportWarning::code, ImportWarning::params)
			.containsExactly(warning(Code.SECURITY_SCHEME_TOKEN,
					Map.of("scheme", "bearerAuth", "sentAs", "'Authorization: Bearer {{bearerAuth}}'", "variable",
							"{{bearerAuth}}")),
					warning(Code.SECURITY_SCHEME_SECRET, Map.of("scheme", "apiKeyHeader", "sentAs",
							"'X-API-Key: {{apiKeyHeader}}'", "variable", "{{apiKeyHeader}}")));

		assertThat(List.of(ImportWarning.credentialSecret(Credential.BEARER_TOKEN, null, "{{bearerToken}}"),
				ImportWarning.credentialSecret(Credential.ACCESS_TOKEN, null, "{{accessToken}}"),
				ImportWarning.credentialSecret(Credential.API_KEY, null, "{{apiKey}}"),
				ImportWarning.credentialSecret(Credential.HEADER, "Cookie", "{{cookie}}"),
				ImportWarning.credentialReplaced(Credential.HEADER, "Authorization", "{{authorization}}"),
				ImportWarning.credentialReplaced(Credential.API_KEY, null, "{{apiKey}}")))
			.extracting(ImportWarning::code, ImportWarning::params)
			.containsExactly(
					warning(Code.CREDENTIAL_TOKEN, Map.of("credential", "bearerToken", "variable", "{{bearerToken}}")),
					warning(Code.CREDENTIAL_TOKEN, Map.of("credential", "accessToken", "variable", "{{accessToken}}")),
					warning(Code.CREDENTIAL_SECRET, Map.of("credential", "apiKey", "variable", "{{apiKey}}")),
					warning(Code.CREDENTIAL_SECRET,
							Map.of("credential", "header", "header", "Cookie", "variable", "{{cookie}}")),
					warning(Code.CREDENTIAL_REPLACED,
							Map.of("credential", "header", "header", "Authorization", "variable", "{{authorization}}")),
					warning(Code.CREDENTIAL_REPLACED, Map.of("credential", "apiKey", "variable", "{{apiKey}}")));
	}

	@Test
	void keepsTheEnglishMessageForOtherClients() {
		assertThat(ImportWarning.credentialReplaced(Credential.HEADER, "Authorization", "{{authorization}}").message())
			.isEqualTo("literal 'Authorization' header value was replaced with {{authorization}}");
		assertThat(ImportWarning.scriptUnsupported("test").message())
			.isEqualTo("test script is not supported; add assertions instead");
		assertThat(ImportWarning.bodyTooLong(1000, false).message())
			.isEqualTo("request body exceeds 1000 characters; body left empty");
	}

	private static Tuple warning(Code code, Map<String, String> params) {
		return Tuple.tuple(code, params);
	}

}
