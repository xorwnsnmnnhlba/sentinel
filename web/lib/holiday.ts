/** 휴일 화면 입력 확인·표시 (api/README.md "휴일"). 날짜는 KST 기준 YYYY-MM-DD */

/** Spring 컬럼 길이와 일치 */
export const MAX_NAME_LENGTH = 100;

/** 한 번에 등록할 수 있는 휴일 수 (Spring과 일치) */
export const MAX_IMPORT = 100;

/**
 * 붙여 넣은 줄의 날짜와 나머지. 2026-12-25, 2026.12.25, 2026/12/25, 2026. 12. 25. (월·일 한 자리 가능)
 * 날짜 바로 뒤 요일 "(금)"은 무시 (달력에서 복사한 목록)
 */
const LINE = /^(\d{4})\s*[-./]\s*(\d{1,2})\s*[-./]\s*(\d{1,2})\.?\s*(?:\([월화수목금토일]\))?[\s,]*(.*)$/;

export type ParsedHolidays = { holidays: { date: string; name: string }[] } | { error: string };

/** 조회할 수 있는 연도 범위. 주소를 직접 고친 값이 너무 크거나 작으면 올해로 */
const MIN_YEAR = 2000;
const MAX_YEAR = 2100;

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

const kstDate = new Intl.DateTimeFormat("en-CA", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

/** 오늘 날짜 (KST). 서버·브라우저 시간대와 무관 */
export function kstToday(now: Date): string {
  return kstDate.format(now);
}

/** 화면 주소의 연도. 없거나 올바르지 않으면 올해 */
export function yearOf(param: string | undefined, today: string): number {
  const year = Number(param);
  return Number.isInteger(year) && year >= MIN_YEAR && year <= MAX_YEAR ? year : Number(today.slice(0, 4));
}

/** 요일 한 글자. 날짜 문자열만으로 계산하므로 시간대 무관 */
export function weekday(date: string): string {
  return WEEKDAYS[new Date(`${date}T00:00:00Z`).getUTCDay()];
}

/** 입력 확인. 문제가 없으면 null */
export function holidayError(date: string, name: string): string | null {
  if (!isDate(date)) {
    return "날짜를 입력해 주세요";
  }
  if (name.trim() === "") {
    return "이름을 입력해 주세요";
  }
  if (name.trim().length > MAX_NAME_LENGTH) {
    return `이름은 ${MAX_NAME_LENGTH}자 이하로 입력해 주세요`;
  }
  return null;
}

/**
 * 붙여 넣은 여러 줄을 휴일 목록으로. 한 줄에 "날짜 이름" 하나, 빈 줄은 무시. 날짜와 이름 사이는 공백·탭·쉼표
 * 잘못된 줄이 있으면 첫 줄 번호와 이유 (전부 등록하지 않는 Spring 규칙과 같게 화면에서도 미리 알림)
 */
export function parseHolidayLines(text: string): ParsedHolidays {
  const holidays: { date: string; name: string }[] = [];
  const lines = text.split(/\r?\n/);
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index].trim();
    if (line === "") {
      continue;
    }
    const at = `${index + 1}번째 줄`;
    const match = LINE.exec(line);
    if (!match) {
      return { error: `${at}: 날짜를 읽을 수 없습니다 (예: 2026-12-25 성탄절)` };
    }
    const date = `${match[1]}-${match[2].padStart(2, "0")}-${match[3].padStart(2, "0")}`;
    const name = match[4].trim();
    if (!isDate(date)) {
      return { error: `${at}: ${date}은(는) 없는 날짜입니다` };
    }
    const error = holidayError(date, name);
    if (error) {
      return { error: `${at}: ${error}` };
    }
    if (holidays.some((holiday) => holiday.date === date)) {
      return { error: `${at}: ${date}이(가) 앞 줄에 이미 있습니다` };
    }
    holidays.push({ date, name });
  }
  if (holidays.length === 0) {
    return { error: "붙여 넣은 휴일이 없습니다" };
  }
  if (holidays.length > MAX_IMPORT) {
    return { error: `한 번에 ${MAX_IMPORT}건까지 등록할 수 있습니다 (지금 ${holidays.length}건)` };
  }
  return { holidays };
}

/** 오늘 이후(오늘 포함) 가장 가까운 휴일 표시. 예: "오늘 임시공휴일", "다음 10-09 한글날". 목록은 날짜 순 */
export function upcomingLabel(holidays: { date: string; name: string }[], today: string): string | undefined {
  const next = holidays.find((holiday) => holiday.date >= today);
  if (!next) {
    return undefined;
  }
  return next.date === today ? `오늘 ${next.name}` : `다음 ${next.date.slice(5)} ${next.name}`;
}

/** 있는 날짜인지 (2026-02-30 등 제외) */
function isDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return false;
  }
  const parsed = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().startsWith(value);
}
