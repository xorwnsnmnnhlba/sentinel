import type { CaseResponseTime } from "@/lib/api/types";

/** 조회 기간(일). Spring 최대 90일 */
export const PERIODS = [7, 30, 90] as const;

export type Period = (typeof PERIODS)[number];

export const DEFAULT_PERIOD: Period = 7;

export function parsePeriod(value: string | undefined): Period {
  const days = Number(value);
  return PERIODS.find((period) => period === days) ?? DEFAULT_PERIOD;
}

const kstDate = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul" });

/**
 * 오늘(KST)까지 days일. 서버·브라우저 시간대와 무관하게 KST 날짜 (YYYY-MM-DD)
 */
export function kstDateRange(days: number, now: Date = new Date()): { from: string; to: string } {
  const to = kstDate.format(now);
  const start = new Date(`${to}T00:00:00Z`);
  start.setUTCDate(start.getUTCDate() - (days - 1));
  return { from: start.toISOString().slice(0, 10), to };
}

/**
 * 기본으로 보여줄 케이스. 기간 중 p95가 가장 큰(가장 느린) 케이스, 결과가 없으면 null
 */
export function slowestCase(cases: CaseResponseTime[]): CaseResponseTime | null {
  return cases.reduce<CaseResponseTime | null>(
    (slowest, current) =>
      current.p95 !== null && (slowest === null || current.p95 > (slowest.p95 ?? -1)) ? current : slowest,
    null,
  );
}

/**
 * 0부터 시작하는 눈금. 1·2·5 × 10ⁿ 간격 중 count칸 안팎으로 max를 덮는 값
 */
export function niceTicks(max: number, count = 4): number[] {
  if (!(max > 0)) {
    return [0, 1];
  }
  const rough = max / count;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  // ms 단위라 1 미만 간격은 쓰지 않음
  const step = Math.max(
    1,
    [1, 2, 5, 10].map((factor) => factor * magnitude).find((candidate) => candidate >= rough)!,
  );
  const ticks: number[] = [];
  for (let tick = 0; tick < max + step; tick += step) {
    ticks.push(tick);
  }
  return ticks;
}

/**
 * 선 경로. 값이 없는 날에서 끊어 새 구간을 시작 (없는 날을 이어 그리지 않음)
 */
export function linePath(
  values: (number | null)[],
  x: (index: number) => number,
  y: (value: number) => number,
): string {
  let path = "";
  let drawing = false;
  values.forEach((value, index) => {
    if (value === null) {
      drawing = false;
      return;
    }
    path += `${drawing ? "L" : "M"}${round(x(index))},${round(y(value))}`;
    drawing = true;
  });
  return path;
}

/** 앞뒤 날짜에 값이 없어 선으로 그려지지 않는 점. 표식으로 표시 */
export function isolatedIndexes(values: (number | null)[]): number[] {
  return values.flatMap((value, index) =>
    value !== null && (values[index - 1] ?? null) === null && (values[index + 1] ?? null) === null ? [index] : [],
  );
}

/** 포인터 위치에서 가장 가까운 날짜 순번 */
export function nearestIndex(pointerX: number, count: number, left: number, width: number): number {
  if (count <= 1) {
    return 0;
  }
  const index = Math.round(((pointerX - left) / width) * (count - 1));
  return Math.min(count - 1, Math.max(0, index));
}

/** x축에 표시할 날짜 순번. 처음·끝을 포함해 최대 count개 */
export function tickIndexes(count: number, max = 5): number[] {
  if (count <= max) {
    return Array.from({ length: count }, (_, index) => index);
  }
  return Array.from({ length: max }, (_, i) => Math.round((i * (count - 1)) / (max - 1)));
}

const number = new Intl.NumberFormat("ko-KR");

export function formatMs(value: number | null): string {
  return value === null ? "–" : `${number.format(value)}ms`;
}

function round(value: number): number {
  return Math.round(value * 10) / 10;
}
