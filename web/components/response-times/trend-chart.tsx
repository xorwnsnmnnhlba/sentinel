"use client";

import { useEffect, useRef, useState } from "react";

import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { ResponseTimePoint } from "@/lib/api/types";
import { formatMs, isolatedIndexes, linePath, nearestIndex, niceTicks, tickIndexes } from "@/lib/response-times";

/** 두 계열. 색은 globals.css의 검증된 계열 토큰, 글자는 항상 텍스트 색 */
const SERIES = [
  { key: "p50", label: "p50", description: "중앙값", color: "var(--chart-series-1)" },
  { key: "p95", label: "p95", description: "느린 5%의 경계", color: "var(--chart-series-2)" },
] as const;

const PLOT_HEIGHT = 200;
const MARGIN = { top: 12, right: 44, bottom: 28, left: 56 };
/** x축 글자 영역을 포함한 전체 높이. 카드 안 세로 스크롤 방지 */
const HEIGHT = PLOT_HEIGHT + MARGIN.top + MARGIN.bottom;
/** x축 날짜 라벨 사이 최소 간격. 좁은 화면에서 라벨이 겹치지 않도록 너비에 따라 개수 조절 */
const MIN_TICK_SPACING = 70;
/** 끝 라벨이 이보다 가까우면 겹치므로 생략하고 범례·툴팁에 맡김 */
const MIN_LABEL_GAP = 14;

/**
 * 일별 p50·p95 선 차트. 값이 없는 날은 선을 끊고, 포인터·키보드로 날짜를 골라 값을 확인. 표 보기 전환 제공
 */
export function TrendChart({ points, title }: { points: ResponseTimePoint[]; title: string }) {
  const [view, setView] = useState<"chart" | "table">("chart");
  const hasData = points.some((point) => point.samples > 0);

  return (
    <figure className="space-y-3">
      <figcaption className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-sm font-medium">{title}</span>
        {hasData && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => setView((current) => (current === "chart" ? "table" : "chart"))}
          >
            {view === "chart" ? "표로 보기" : "차트로 보기"}
          </Button>
        )}
      </figcaption>
      {!hasData ? (
        <p className="rounded-lg border border-dashed p-10 text-center text-sm text-muted-foreground">
          이 기간에 응답을 받은 실행이 없습니다
        </p>
      ) : view === "chart" ? (
        <Chart points={points} title={title} />
      ) : (
        <TrendTable points={points} />
      )}
    </figure>
  );
}

function Chart({ points, title }: { points: ResponseTimePoint[]; title: string }) {
  const container = useRef<HTMLDivElement>(null);
  const width = useWidth(container);
  const [active, setActive] = useState<number | null>(null);

  const plotWidth = Math.max(1, width - MARGIN.left - MARGIN.right);
  const values = SERIES.map((series) => points.map((point) => point[series.key]));
  const ticks = niceTicks(Math.max(...values.flat().map((value) => value ?? 0)));
  const yMax = ticks[ticks.length - 1];
  const x = (index: number) =>
    MARGIN.left + (points.length === 1 ? plotWidth / 2 : (index * plotWidth) / (points.length - 1));
  const y = (value: number) => MARGIN.top + PLOT_HEIGHT - (value / yMax) * PLOT_HEIGHT;

  const endLabels = endLabelPositions(values, y);

  function move(index: number | null) {
    setActive(index === null ? null : Math.min(points.length - 1, Math.max(0, index)));
  }

  function onKeyDown(event: React.KeyboardEvent) {
    const last = lastIndexWithData(points);
    const keys: Record<string, () => number | null> = {
      ArrowLeft: () => (active ?? last) - 1,
      ArrowRight: () => (active ?? last) + 1,
      Home: () => 0,
      End: () => points.length - 1,
      Escape: () => null,
    };
    if (keys[event.key]) {
      event.preventDefault();
      move(keys[event.key]());
    }
  }

  const activePoint = active === null ? null : points[active];

  return (
    <div className="space-y-2">
      <Legend />
      <div ref={container} className="relative" style={{ height: HEIGHT }}>
        <svg
          width={width}
          height={HEIGHT}
          role="img"
          aria-label={`${title}. 왼쪽·오른쪽 방향키로 날짜별 값을 확인합니다`}
          tabIndex={0}
          className="overflow-visible outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
          onKeyDown={onKeyDown}
          onFocus={() => {
            if (active === null) {
              move(lastIndexWithData(points));
            }
          }}
          onBlur={() => move(null)}
          onPointerMove={(event) => {
            const bounds = event.currentTarget.getBoundingClientRect();
            move(nearestIndex(event.clientX - bounds.left, points.length, MARGIN.left, plotWidth));
          }}
          onPointerLeave={() => move(null)}
        >
          {/* 눈금선: 표면에서 한 단계 떨어진 회색 실선 1px */}
          {ticks.map((tick) => (
            <g key={tick}>
              <line
                x1={MARGIN.left}
                x2={MARGIN.left + plotWidth}
                y1={y(tick)}
                y2={y(tick)}
                stroke="var(--border)"
                strokeWidth={1}
              />
              <text
                x={MARGIN.left - 8}
                y={y(tick)}
                textAnchor="end"
                dominantBaseline="middle"
                className="fill-muted-foreground text-[11px] tabular-nums"
              >
                {tick.toLocaleString("ko-KR")}ms
              </text>
            </g>
          ))}
          {tickIndexes(points.length, Math.max(2, Math.min(6, Math.floor(plotWidth / MIN_TICK_SPACING)))).map(
            (index) => (
              <text
                key={index}
                x={x(index)}
                y={HEIGHT - 8}
                textAnchor={index === 0 ? "start" : index === points.length - 1 ? "end" : "middle"}
                className="fill-muted-foreground text-[11px] tabular-nums"
              >
                {points[index].date.slice(5)}
              </text>
            ),
          )}

          {SERIES.map((series, s) => (
            <g key={series.key}>
              <path
                d={linePath(values[s], x, y)}
                fill="none"
                stroke={series.color}
                strokeWidth={2}
                strokeLinecap="round"
                strokeLinejoin="round"
              />
              {/* 앞뒤 날짜가 비어 선이 없는 점은 표식으로 */}
              {isolatedIndexes(values[s]).map((index) => (
                <circle
                  key={index}
                  cx={x(index)}
                  cy={y(values[s][index]!)}
                  r={4}
                  fill={series.color}
                  stroke="var(--background)"
                  strokeWidth={2}
                />
              ))}
              {endLabels[s] && (
                <text
                  x={x(endLabels[s].index) + 8}
                  y={endLabels[s].y}
                  dominantBaseline="middle"
                  className="fill-muted-foreground text-[11px]"
                >
                  {series.label}
                </text>
              )}
            </g>
          ))}

          {active !== null && (
            <g pointerEvents="none">
              <line
                x1={x(active)}
                x2={x(active)}
                y1={MARGIN.top}
                y2={MARGIN.top + PLOT_HEIGHT}
                stroke="var(--muted-foreground)"
                strokeWidth={1}
              />
              {SERIES.map((series, s) =>
                values[s][active] === null ? null : (
                  <circle
                    key={series.key}
                    cx={x(active)}
                    cy={y(values[s][active]!)}
                    r={4}
                    fill={series.color}
                    stroke="var(--background)"
                    strokeWidth={2}
                  />
                ),
              )}
            </g>
          )}
        </svg>
        {activePoint && active !== null && (
          <Tooltip point={activePoint} left={x(active)} alignRight={x(active) > width / 2} />
        )}
      </div>
    </div>
  );
}

function Legend() {
  return (
    <ul className="flex flex-wrap gap-4 text-xs text-muted-foreground" aria-label="범례">
      {SERIES.map((series) => (
        <li key={series.key} className="flex items-center gap-1.5">
          {/* 선 차트 범례는 선 모양 */}
          <span aria-hidden className="h-0.5 w-4 rounded-full" style={{ backgroundColor: series.color }} />
          <span className="text-foreground">{series.label}</span>
          <span>{series.description}</span>
        </li>
      ))}
    </ul>
  );
}

function Tooltip({ point, left, alignRight }: { point: ResponseTimePoint; left: number; alignRight: boolean }) {
  return (
    <div
      role="status"
      className="pointer-events-none absolute top-0 z-10 min-w-36 rounded-md border bg-popover px-3 py-2 text-xs shadow-md"
      style={alignRight ? { right: `calc(100% - ${left - 12}px)` } : { left: left + 12 }}
    >
      <p className="mb-1 text-muted-foreground">
        {point.date} · {point.samples}건
      </p>
      {point.samples === 0 ? (
        <p className="text-muted-foreground">응답 없음</p>
      ) : (
        <ul className="space-y-0.5">
          {rows(point).map((row) => (
            <li key={row.label} className="flex items-center gap-2">
              {/* 툴팁은 상자 대신 짧은 선으로 계열 표시, 값이 앞 */}
              <span aria-hidden className="h-0.5 w-3 rounded-full" style={{ backgroundColor: row.color }} />
              <span className="font-semibold text-foreground tabular-nums">{formatMs(row.value)}</span>
              <span className="text-muted-foreground">{row.label}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function rows(point: ResponseTimePoint) {
  return [
    ...SERIES.map((series) => ({ label: series.label, color: series.color, value: point[series.key] })),
    { label: "최대", color: "transparent", value: point.max },
  ];
}

/** 차트와 같은 값을 표로. 툴팁 없이도 모든 값을 읽을 수 있도록 함 */
function TrendTable({ points }: { points: ResponseTimePoint[] }) {
  return (
    <div className="max-h-80 overflow-y-auto rounded-lg border bg-card">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>날짜 (KST)</TableHead>
            <TableHead className="text-right">건수</TableHead>
            <TableHead className="text-right">p50</TableHead>
            <TableHead className="text-right">p95</TableHead>
            <TableHead className="text-right">최대</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {points.map((point) => (
            <TableRow key={point.date}>
              <TableCell className="font-mono text-xs">{point.date}</TableCell>
              <TableCell className="text-right tabular-nums">{point.samples}</TableCell>
              <TableCell className="text-right tabular-nums">{formatMs(point.p50)}</TableCell>
              <TableCell className="text-right tabular-nums">{formatMs(point.p95)}</TableCell>
              <TableCell className="text-right tabular-nums">{formatMs(point.max)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

/**
 * 계열별 끝 라벨 위치 (마지막 값). 두 라벨이 겹칠 만큼 가까우면 둘 다 생략 (위아래로 밀면 선과 떨어져 오히려 혼동)
 */
function endLabelPositions(values: (number | null)[][], y: (value: number) => number) {
  const positions = values.map((series) => {
    const index = series.findLastIndex((value) => value !== null);
    return index < 0 ? null : { index, y: y(series[index]!) };
  });
  const [first, second] = positions;
  if (first && second && first.index === second.index && Math.abs(first.y - second.y) < MIN_LABEL_GAP) {
    return [null, null];
  }
  return positions;
}

function lastIndexWithData(points: ResponseTimePoint[]): number {
  const index = points.findLastIndex((point) => point.samples > 0);
  return index < 0 ? points.length - 1 : index;
}

/** 컨테이너 너비. 측정 전(서버 렌더링·jsdom)에는 기본값 */
function useWidth(ref: React.RefObject<HTMLDivElement | null>): number {
  const [width, setWidth] = useState(640);
  useEffect(() => {
    const element = ref.current;
    if (!element || typeof ResizeObserver === "undefined") {
      return;
    }
    const observer = new ResizeObserver(([entry]) => setWidth(Math.round(entry.contentRect.width)));
    observer.observe(element);
    return () => observer.disconnect();
  }, [ref]);
  return width;
}
