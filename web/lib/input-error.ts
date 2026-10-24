import { codedText, lookup, type ParamFormat } from "@/lib/coded-text";

/**
 * 입력 값 오류(400) 문장. 서버가 보낸 code·params로 만들고(lib/coded-text.ts), 모르는 code나 빠진 값이 있으면 null
 * (Spring InvalidValueException.Code와 같은 목록)
 */
export const INPUT_ERROR_TEXTS = {
  INVALID_JSON_PATH: "JsonPath 문법이 올바르지 않습니다: {expression}",
  INVALID_REGEX: "정규식 문법이 올바르지 않습니다: {expression}",
  CRON_FIELD_COUNT: "cron은 분 시 일 월 요일 5칸을 공백으로 구분해 입력해 주세요 (입력: {cron})",
  CRON_INVALID_FIELD: "cron의 {field} 칸이 올바르지 않습니다: {value}",
  CRON_INVALID: "cron 형식이 올바르지 않습니다: {cron}",
  CRON_NEVER_FIRES: "실행 시각이 오지 않는 cron입니다 (예: 2월 30일): {cron}",
  REQUIRED: "{target}: 값을 입력해 주세요",
  TOO_LONG: "{target}: {max}자 이하로 입력해 주세요",
  BASE_URL_INVALID: "기본 URL을 주소로 읽을 수 없습니다 (공백 등 주소에 쓸 수 없는 문자 확인): {value}",
  BASE_URL_SCHEME: "기본 URL은 http:// 또는 https://로 시작해야 합니다: {value}",
  BASE_URL_HOST: "기본 URL에 서버 주소가 없습니다 (예: https://api.example.com): {value}",
  BASE_URL_QUERY: "기본 URL에는 ?·# 뒤의 값을 넣을 수 없습니다. query는 케이스 경로에 넣어 주세요: {value}",
  USED_AS_SECRET: "변수 {name}: 같은 이름의 시크릿이 있습니다. 이름을 바꾸거나 한쪽을 지워 주세요",
  USED_AS_VARIABLE: "시크릿 {name}: 같은 이름의 변수가 있습니다. 이름을 바꾸거나 한쪽을 지워 주세요",
  EMAIL_INVALID: "이메일 형식이 올바르지 않습니다: {email}",
  SECRETS_DISABLED: "시크릿 기능이 꺼져 있습니다. 서버에 암호화 키(SENTINEL_SECRET_KEY) 설정이 필요합니다",
  SECRETS_KEY_MISSING:
    "서버에 암호화 키(SENTINEL_SECRET_KEY)가 설정되지 않아 이 환경의 시크릿을 읽을 수 없습니다. 관리자에게 알려 주세요",
  SECRETS_UNREADABLE:
    "이 환경의 시크릿을 읽을 수 없습니다. 서버 암호화 키가 바뀌었다면 환경 수정에서 시크릿 값을 다시 입력해 주세요",
} as const;

/** 빈 값·길이 초과 항목 이름 (서버의 항목 이름 → 화면 이름). 목록에 없으면 그대로 */
const TARGETS: Record<string, string> = {
  name: "이름",
  description: "설명",
  baseUrl: "기본 URL",
  cron: "cron",
  path: "경로",
  "variable key": "변수 이름",
  "secret name": "시크릿 이름",
  "header name": "헤더 이름",
  email: "이메일",
  password: "비밀번호",
  currentPassword: "현재 비밀번호",
  newPassword: "새 비밀번호",
  reason: "사유",
  content: "문서",
  date: "날짜",
};

/** cron 칸과 쓸 수 있는 값 (Spring CronExpression 기준) */
const CRON_FIELDS: Record<string, string> = {
  minute: "분(0~59)",
  hour: "시(0~23)",
  day: "일(1~31)",
  month: "월(1~12, JAN~DEC)",
  weekday: "요일(0~7, SUN~SAT. 0과 7은 일요일)",
};

/** 오류가 난 목록과 그 안의 순서 (케이스의 판정 규칙·값 추출) */
const LISTS: Record<string, string> = {
  assertions: "판정 규칙",
  extractors: "값 추출",
};

const PARAM_FORMATS: Record<string, ParamFormat> = {
  field: (value) => lookup(CRON_FIELDS, value),
  target: (value) => lookup(TARGETS, value),
};

export function describeInputError(code: string | undefined, params: Record<string, string>): string | null {
  const text = code === undefined ? null : codedText(INPUT_ERROR_TEXTS, PARAM_FORMATS, code, params);
  if (text === null) {
    return null;
  }
  // 위치는 서버가 JSON을 읽다 찾은 오류에만 있음
  const list = params.list !== undefined && Object.hasOwn(LISTS, params.list) ? LISTS[params.list] : undefined;
  return list !== undefined && /^\d+$/.test(params.index ?? "") ? `${list} ${Number(params.index) + 1}: ${text}` : text;
}
