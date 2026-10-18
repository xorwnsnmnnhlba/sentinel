import { ArrowRight, Check } from "lucide-react";
import Link from "next/link";

import { Panel } from "@/components/catalog/section";
import { Button } from "@/components/ui/button";
import type { StartStep } from "@/lib/getting-started";
import { cn } from "@/lib/utils";

/**
 * 대시보드의 처음 시작하기. 스케줄 실행까지의 단계, 끝난 단계는 체크, 지금 할 수 있는 단계는 그 화면으로 가는 버튼
 * 첫 번째 남은 단계를 현재 단계(aria-current="step")로 표시
 */
export function GettingStarted({ steps }: { steps: StartStep[] }) {
  const done = steps.filter((step) => step.done).length;
  const current = steps.findIndex((step) => !step.done);
  return (
    <Panel title="처음 시작하기" meta={`${steps.length}단계 중 ${done}단계 완료`}>
      <ol className="divide-y text-sm" aria-label="처음 시작하기 단계">
        {steps.map((step, index) => (
          <li
            key={step.title}
            aria-current={index === current ? "step" : undefined}
            className={cn("flex items-center gap-3 px-4 py-2.5", index === current && "bg-muted/50")}
          >
            <span
              aria-hidden
              className={cn(
                "flex size-6 shrink-0 items-center justify-center rounded-full border font-mono text-xs",
                step.done && "border-transparent bg-emerald-500/15 text-emerald-800 dark:text-emerald-400",
              )}
            >
              {step.done ? <Check className="size-3.5" /> : index + 1}
            </span>
            <div className="min-w-0 flex-1">
              <p className={cn("font-medium", step.done && "text-muted-foreground line-through")}>
                {step.title}
                {step.done && <span className="sr-only"> (완료)</span>}
              </p>
              {!step.done && <p className="text-xs text-muted-foreground">{step.detail}</p>}
            </div>
            {step.href && (
              <Button asChild variant={index === current ? "default" : "ghost"} size="sm">
                <Link href={step.href}>
                  이동 <ArrowRight />
                  <span className="sr-only">: {step.title}</span>
                </Link>
              </Button>
            )}
          </li>
        ))}
      </ol>
    </Panel>
  );
}
