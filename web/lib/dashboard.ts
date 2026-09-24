import type { SuiteSummary } from "@/lib/api/types";

/** 대시보드 기간(일) */
export const DASHBOARD_PERIODS = [7, 30] as const;

export type DashboardPeriod = (typeof DASHBOARD_PERIODS)[number];

export function parseDashboardPeriod(value: string | undefined): DashboardPeriod {
  return DASHBOARD_PERIODS.find((period) => period === Number(value)) ?? 7;
}

/** 바로 앞의 같은 길이 기간 (YYYY-MM-DD) */
export function previousRange(from: string, days: number): { from: string; to: string } {
  const start = new Date(`${from}T00:00:00Z`);
  const shift = (offset: number) => {
    const date = new Date(start);
    date.setUTCDate(date.getUTCDate() + offset);
    return date.toISOString().slice(0, 10);
  };
  return { from: shift(-days), to: shift(-1) };
}

export interface Totals {
  runs: number;
  failingRuns: number;
  results: number;
  passed: number;
  /** 결과가 없으면 null */
  passRate: number | null;
}

/** 스위트 요약 합계. 통과율은 스위트 비율의 평균이 아닌 전체 결과 기준. 모든 지표가 같은 스위트 범위(보관 제외)에서 나옴 */
export function totals(suites: SuiteSummary[]): Totals {
  const runs = suites.reduce((sum, suite) => sum + suite.runs, 0);
  const failingRuns = suites.reduce((sum, suite) => sum + suite.failingRuns, 0);
  const results = suites.reduce((sum, suite) => sum + suite.results.total, 0);
  const passed = suites.reduce((sum, suite) => sum + suite.results.passed, 0);
  return { runs, failingRuns, results, passed, passRate: results === 0 ? null : passed / results };
}

/** 좋아짐·나빠짐·중립. 색은 방향 × 늘어나는 게 좋은지로 정함 */
export type Tone = "good" | "bad" | "neutral";

export interface Delta {
  direction: "up" | "down" | "flat";
  tone: Tone;
  /** 예: "+3건", "−1.5%p" */
  text: string;
}

/**
 * 이전 기간 대비 변화
 * @param higherIsBetter 증가가 좋은 지표면 true, 나쁜 지표면 false, 좋고 나쁨이 없으면 null
 */
export function delta(
  current: number | null,
  previous: number | null,
  format: (difference: number) => string,
  higherIsBetter: boolean | null,
): Delta | null {
  if (current === null || previous === null) {
    return null;
  }
  const difference = current - previous;
  // 부동소수 오차로 0이 아닌 값이 되는 경우 (통과율 등)
  if (Math.abs(difference) < 1e-9) {
    return { direction: "flat", tone: "neutral", text: "변화 없음" };
  }
  const up = difference > 0;
  const tone: Tone = higherIsBetter === null ? "neutral" : up === higherIsBetter ? "good" : "bad";
  return { direction: up ? "up" : "down", tone, text: `${up ? "+" : "−"}${format(Math.abs(difference))}` };
}

const count = new Intl.NumberFormat("ko-KR");

export function formatCount(value: number): string {
  return count.format(value);
}

/** 비율(0~1)을 백분율로. 100%가 아닌데 반올림으로 100.0%가 되지 않도록 내림 */
export function formatPercent(ratio: number | null): string {
  if (ratio === null) {
    return "–";
  }
  const percent = Math.floor(ratio * 1000) / 10;
  return `${percent.toFixed(ratio === 1 ? 0 : 1)}%`;
}

/** 증가율. 예: 0.42 → "+42%", −0.25 → "−25%" */
export function formatChange(change: number): string {
  const percent = Math.round(change * 100);
  return `${percent > 0 ? "+" : percent < 0 ? "−" : ""}${Math.abs(percent)}%`;
}

/**
 * 통과율 낮은 순. 결과가 없는 스위트는 뒤로, 같으면 실행이 많은 순
 */
export function byPassRate(suites: SuiteSummary[]): SuiteSummary[] {
  return [...suites].sort((a, b) => {
    if (a.passRate === null || b.passRate === null) {
      return a.passRate === b.passRate ? b.runs - a.runs : a.passRate === null ? 1 : -1;
    }
    return a.passRate - b.passRate || b.runs - a.runs;
  });
}
