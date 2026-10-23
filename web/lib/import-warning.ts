import type { ImportWarning } from "@/lib/api/types";

/**
 * 가져오기 경고·안내 문장. 서버가 보낸 code·params로 만들고, 모르는 code(서버가 먼저 업데이트된 경우 등)나
 * 빠진 값이 있으면 서버의 영어 문장 그대로 (Spring ImportWarning.Code와 같은 목록)
 * 문장의 {이름}은 params 값. 조사가 값에 따라 달라지지 않도록 "대상: 설명" 형태
 */
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
  if (!Object.hasOwn(IMPORT_WARNING_TEXTS, code)) {
    return message;
  }
  let complete = true;
  const text = IMPORT_WARNING_TEXTS[code as keyof typeof IMPORT_WARNING_TEXTS].replace(/\{(\w+)\}/g, (_, name) => {
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
