"use client";

import { ArrowDown, ArrowUp } from "lucide-react";
import { useEffect, useState } from "react";

import { Button } from "@/components/ui/button";
import type { HttpMethod } from "@/lib/api/types";

export type OrderedCase = { id: number; name: string; method: HttpMethod; path: string };

type Direction = "up" | "down";

/**
 * 케이스 실행 순서 편집 (순서 변경 대화상자의 폼 내용). 위·아래 버튼으로 한 칸씩 이동하고 caseId를 현재 순서대로 제출
 *
 * 끌어서 놓기 대신 버튼을 써서 키보드·화면 낭독기로도 조작 가능. 옮긴 뒤 같은 케이스의 버튼에 초점을 유지하고
 * (끝에 닿아 버튼이 비활성화되면 반대쪽 버튼) 바뀐 위치를 알림 영역으로 안내
 */
export function CaseOrderFields({ cases }: { cases: OrderedCase[] }) {
  const [order, setOrder] = useState(cases);
  const [moved, setMoved] = useState<{ id: number; direction: Direction } | null>(null);
  const [announcement, setAnnouncement] = useState("");

  useEffect(() => {
    if (!moved) {
      return;
    }
    const index = order.findIndex((testCase) => testCase.id === moved.id);
    const atEdge = moved.direction === "up" ? index === 0 : index === order.length - 1;
    const direction = atEdge ? (moved.direction === "up" ? "down" : "up") : moved.direction;
    document.getElementById(buttonId(moved.id, direction))?.focus();
  }, [moved, order]);

  function move(index: number, direction: Direction) {
    const target = direction === "up" ? index - 1 : index + 1;
    const next = [...order];
    [next[index], next[target]] = [next[target], next[index]];
    setOrder(next);
    setMoved({ id: order[index].id, direction });
    setAnnouncement(`${order[index].name}: ${order.length}개 중 ${target + 1}번째`);
  }

  return (
    <div className="space-y-2">
      <ol aria-label="실행 순서" className="max-h-96 divide-y overflow-auto rounded-md border">
        {order.map((testCase, index) => (
          <li key={testCase.id} className="flex items-center gap-3 px-3 py-2">
            <input type="hidden" name="caseId" value={testCase.id} />
            <span className="w-6 shrink-0 text-right text-sm text-muted-foreground tabular-nums">{index + 1}</span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{testCase.name}</p>
              <p className="truncate font-mono text-xs text-muted-foreground">
                {testCase.method} {testCase.path}
              </p>
            </div>
            <div className="flex shrink-0 gap-1">
              <Button
                id={buttonId(testCase.id, "up")}
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label={`${testCase.name} 위로 이동`}
                disabled={index === 0}
                onClick={() => move(index, "up")}
              >
                <ArrowUp />
              </Button>
              <Button
                id={buttonId(testCase.id, "down")}
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label={`${testCase.name} 아래로 이동`}
                disabled={index === order.length - 1}
                onClick={() => move(index, "down")}
              >
                <ArrowDown />
              </Button>
            </div>
          </li>
        ))}
      </ol>
      <p aria-live="polite" className="sr-only">
        {announcement}
      </p>
    </div>
  );
}

function buttonId(caseId: number, direction: Direction) {
  return `case-order-${caseId}-${direction}`;
}
