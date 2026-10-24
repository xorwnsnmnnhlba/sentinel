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
} as const;

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
