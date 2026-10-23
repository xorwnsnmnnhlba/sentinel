import type { ImportWarning } from "@/lib/api/types";

/**
 * 가져오기 경고·오류 문장. 서버가 보낸 code·params로 만들고, 모르는 code(서버가 먼저 업데이트된 경우 등)나
 * 빠진 값이 있으면 서버의 영어 문장 그대로
 * 문장의 {이름}은 params 값. 조사가 값에 따라 달라지지 않도록 "대상: 설명" 형태
 */

/** 미리보기의 경고·안내 (Spring ImportWarning.Code와 같은 목록) */
export const IMPORT_WARNING_TEXTS = {
  VARIABLE_RENAMED: "파라미터 '{name}': 변수 이름에 쓸 수 없는 문자를 _로 바꿔 변수 {variable} 사용",
  METHOD_SKIPPED: "{source}: {method} 메서드는 지원하지 않아 제외했습니다",
  DEPRECATED: "사용 중단(deprecated)으로 표시된 작업입니다",
  COOKIE_PARAMETER: "필수 쿠키 파라미터 '{name}': 지원하지 않아 넣지 않았습니다",
  SECURITY_SCHEME_UNDEFINED: "보안 스킴 '{scheme}': 문서에 정의가 없어 인증을 넣지 않았습니다",
  SECURITY_SCHEME_UNSUPPORTED: "보안 스킴 '{scheme}' ({kind}): 지원하지 않는 방식이라 인증을 넣지 않았습니다",
  SECURITY_SCHEME_SECRET: "보안 스킴 '{scheme}' → {sentAs}. 환경에 시크릿 {variable} 등록이 필요합니다",
  SECURITY_SCHEME_TOKEN:
    "보안 스킴 '{scheme}' → {sentAs}. 환경에 시크릿 {variable} 등록이 필요합니다. 로그인 요청이 발급하는 토큰이면 앞 케이스의 값 추출로 {variable}에 받아도 됩니다",
  BODY_MEDIA_TYPE_UNSUPPORTED: "요청 본문 형식 {mediaTypes}: 지원하지 않아 본문을 비워 두었습니다",
  BODY_MISSING: "필수 요청 본문인데 JSON 예시·스키마가 없어 본문을 비워 두었습니다",
  BODY_FROM_SCHEMA: "요청 본문을 스키마로 만들었습니다. 자리 표시 값을 실제 값으로 바꿔 주세요",
  BODY_TOO_LONG: "요청 본문이 {limit}자를 넘어 비워 두었습니다",
  EXTERNAL_EXAMPLE: "외부 예시 '{ref}': 지원하지 않아 사용하지 않았습니다",
  NO_SUCCESS_RESPONSE: "문서에 2xx 응답이 없어 2xx 전체를 성공으로 판정합니다",
  REFERENCE_UNRESOLVED: "{location}: 순환하거나 너무 깊은 참조라 생략했습니다 ('{ref}')",
  REFERENCE_EXTERNAL: "{location}: 외부 파일 참조는 지원하지 않아 생략했습니다 ('{ref}')",
  REFERENCE_NOT_FOUND: "{location}: 참조 대상을 문서에서 찾지 못해 생략했습니다 ('{ref}')",
  GRAPHQL_VARIABLES_INVALID: "GraphQL 변수가 올바른 JSON이 아니라 넣지 않았습니다",
  BODY_MODE_UNSUPPORTED: "본문 형식 '{mode}': 지원하지 않아 본문을 비워 두었습니다",
  BASIC_AUTH_SECRET:
    "Basic 인증 → 'Authorization: Basic {variable}'. 환경에 시크릿 {variable} 등록이 필요합니다 (값: 'username:password'의 base64)",
  API_KEY_NAME_MISSING: "API 키 인증에 키 이름이 없어 넣지 않았습니다",
  AUTH_UNSUPPORTED: "인증 방식 '{type}': 지원하지 않아 넣지 않았습니다",
  CREDENTIAL_REPLACED: "평문 {credential} → 변수 {variable} (케이스에 자격증명을 남기지 않음)",
  CREDENTIAL_SECRET: "환경에 시크릿 {variable} 등록이 필요합니다 ({credential})",
  CREDENTIAL_TOKEN:
    "환경에 시크릿 {variable} 등록이 필요합니다 ({credential}). 로그인 요청이 발급하는 토큰이면 앞 케이스의 값 추출로 {variable}에 받아도 됩니다",
  SCRIPT_UNSUPPORTED: "{script} 스크립트: 지원하지 않습니다. 판정 규칙으로 대신해 주세요",
  COLLECTION_VARIABLES: "컬렉션 변수는 가져오지 않습니다. 환경에 정의해 주세요: {names}",
} as const;

/** 가져올 수 없는 문서 (Spring ImportDocumentException.Code와 같은 목록) */
export const IMPORT_ERROR_TEXTS = {
  EMPTY: "문서가 비어 있습니다",
  TOO_LONG: "문서는 {limit}자까지 가져올 수 있습니다",
  INVALID_SYNTAX: "JSON·YAML 문법이 올바르지 않습니다 ({detail})",
  UNREADABLE: "문서를 해석하지 못했습니다 ({detail})",
  NOT_AN_OBJECT: "문서의 최상위가 객체가 아닙니다. 문서 전체를 넣었는지 확인해 주세요",
  SWAGGER_2: "Swagger {version} 문서입니다. OpenAPI 3으로 변환한 문서를 넣어 주세요",
  NOT_OPENAPI: "openapi 항목이 없어 OpenAPI 문서가 아닙니다. Postman 컬렉션이면 형식을 Postman으로 골라 주세요",
  OPENAPI_VERSION: "OpenAPI 3 문서만 가져올 수 있습니다 (문서의 버전: {version})",
  NO_PATHS: "문서에 경로(paths)가 없습니다",
  NO_OPERATIONS: "문서에 가져올 수 있는 작업이 없습니다 (TRACE만 있는 경우 등)",
  POSTMAN_ENVIRONMENT:
    "Postman 환경 파일입니다. 컬렉션을 내보낸 파일을 넣어 주세요 (환경의 변수는 Sentinel 환경에 직접 등록)",
  POSTMAN_V1: "Postman Collection v1입니다. Postman에서 v2.1로 다시 내보내 주세요",
  NOT_POSTMAN: "info.schema 항목이 없어 Postman 컬렉션이 아닙니다. OpenAPI 문서면 형식을 OpenAPI 3으로 골라 주세요",
  POSTMAN_SCHEMA: "Postman Collection v2.0·v2.1만 가져올 수 있습니다 (문서의 스키마: {schema})",
  NO_REQUESTS: "컬렉션에 가져올 수 있는 요청이 없습니다",
} as const;

/** 참조 위치 중 경로가 아닌 것 */
const LOCATIONS: Record<string, string> = {
  parameter: "파라미터",
  "security scheme": "보안 스킴",
  "request body": "요청 본문",
  example: "예시",
  schema: "스키마",
};

const CREDENTIALS: Record<string, string> = {
  bearerToken: "Bearer 토큰",
  accessToken: "OAuth 2.0 액세스 토큰",
  apiKey: "API 키",
};

const SCRIPTS: Record<string, string> = {
  prerequest: "사전 요청(pre-request)",
  test: "테스트(test)",
};

/** 값을 문장에 넣기 전 한국어로. 목록에 없는 값은 그대로 */
type ParamFormat = (value: string, params: Record<string, string>) => string | undefined;

const PARAM_FORMATS: Record<string, ParamFormat> = {
  location: (value) => lookup(LOCATIONS, value),
  credential: (value, params) =>
    value === "header" ? params.header && `'${params.header}' 헤더 값` : lookup(CREDENTIALS, value),
  script: (value) => lookup(SCRIPTS, value),
  limit: (value) => (/^\d+$/.test(value) ? Number(value).toLocaleString("ko-KR") : value),
};

export function describeImportWarning({ code, params, message }: ImportWarning): string {
  return describe(IMPORT_WARNING_TEXTS, code, params, message);
}

/**
 * @param detail 서버의 영어 메시지. code가 없거나(다른 종류의 400) 모르는 code면 그대로
 */
export function describeImportError(code: string | undefined, params: Record<string, string>, detail: string): string {
  return code === undefined ? detail : describe(IMPORT_ERROR_TEXTS, code, params, detail);
}

function describe(
  texts: Record<string, string>,
  code: string,
  params: Record<string, string>,
  message: string,
): string {
  if (!Object.hasOwn(texts, code)) {
    return message;
  }
  let complete = true;
  const text = texts[code].replace(/\{(\w+)\}/g, (_, name: string) => {
    // 문장의 값 이름은 기본 속성(toString 등)과 겹치지 않아 own 여부는 보지 않음
    const value: string | undefined = params[name];
    const format: ParamFormat | undefined = PARAM_FORMATS[name];
    const formatted = value === undefined || format === undefined ? value : format(value, params);
    if (formatted === undefined) {
      complete = false;
      return "";
    }
    return formatted;
  });
  return complete ? text : message;
}

function lookup(names: Record<string, string>, value: string): string {
  return Object.hasOwn(names, value) ? names[value] : value;
}
