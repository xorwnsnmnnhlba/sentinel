/**
 * 서버가 오류·경고에 붙인 종류(code)와 값(params)으로 한국어 문장 만들기 (lib/import-message.ts, lib/input-error.ts 공용)
 * 문장의 {이름}은 params 값. 조사가 값에 따라 달라지지 않도록 "대상: 설명" 형태로 씀
 */

/** 값을 문장에 넣기 전 한국어로. 넣을 수 없으면 undefined (문장을 만들지 않음) */
export type ParamFormat = (value: string, params: Record<string, string>) => string | undefined;

/**
 * @returns 모르는 code(서버가 먼저 업데이트된 경우 등)나 빠진 값이 있으면 null. 호출하는 쪽이 서버 문구 등으로 대신함
 */
export function codedText(
  texts: Record<string, string>,
  formats: Record<string, ParamFormat>,
  code: string,
  params: Record<string, string>,
): string | null {
  if (!Object.hasOwn(texts, code)) {
    return null;
  }
  let complete = true;
  const text = texts[code].replace(/\{(\w+)\}/g, (_, name: string) => {
    // 문장의 값 이름은 기본 속성(toString 등)과 겹치지 않아 own 여부는 보지 않음
    const value: string | undefined = params[name];
    const format: ParamFormat | undefined = formats[name];
    const formatted = value === undefined || format === undefined ? value : format(value, params);
    if (formatted === undefined) {
      complete = false;
      return "";
    }
    return formatted;
  });
  return complete ? text : null;
}

/** 목록에 있으면 한국어 이름, 없으면 값 그대로 */
export function lookup(names: Record<string, string>, value: string): string {
  return Object.hasOwn(names, value) ? names[value] : value;
}
