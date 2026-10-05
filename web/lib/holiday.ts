/** 휴일 화면 입력 확인·표시 (api/README.md "휴일"). 날짜는 KST 기준 YYYY-MM-DD */

/** Spring 컬럼 길이와 일치 */
export const MAX_NAME_LENGTH = 100;

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
