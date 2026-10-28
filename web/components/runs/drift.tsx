import { ApproveSchemaButton } from "@/components/runs/approve-schema-button";
import { Badge } from "@/components/ui/badge";
import type { ActionState } from "@/lib/actions/catalog";
import type { DriftChange, DriftLevel, ResultDrift } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";
import { cn } from "@/lib/utils";

const levelStyle: Record<DriftLevel, { label: string; className: string }> = {
  BREAKING: { label: "호환 깨짐", className: "bg-destructive/10 text-destructive" },
  WARNING: { label: "주의", className: "bg-amber-500/10 text-amber-800 dark:text-amber-400" },
  INFO: { label: "새 필드", className: "bg-muted text-muted-foreground" },
};

const kindLabel: Record<DriftChange["kind"], string> = {
  REMOVED: "필수 필드 없음",
  TYPE_CHANGED: "타입 변경",
  NULLABLE: "null 등장",
  ADDED: "새 필드",
};

/** 가장 심한 변화. 변화가 없거나 비교 전이면 null */
export function highestLevel(drift: ResultDrift | null): DriftLevel | null {
  return drift?.changes[0]?.level ?? null;
}

/** 결과 목록의 요약 배지. 가장 심한 등급의 색, 전체 변화 수, 승인 대기 여부 */
export function DriftBadge({ drift }: { drift: ResultDrift | null }) {
  const level = highestLevel(drift);
  if (!drift || !level) {
    return null;
  }
  return (
    <Badge className={cn("shrink-0", levelStyle[level].className)}>
      구조 변화 {drift.changes.length}
      {drift.pending && " · 승인 대기"}
      <span className="sr-only"> (가장 심한 등급: {levelStyle[level].label})</span>
    </Badge>
  );
}

/**
 * 구조 탭 상단의 변화 목록. 승인 대기면 기준선 반영 안내와 버튼(approve), 반영한 결과는 반영 기록
 */
export function DriftSummary({ drift, approve }: { drift: ResultDrift; approve?: () => Promise<ActionState> }) {
  if (drift.status === "COLLECTING") {
    return (
      <p className="text-xs text-muted-foreground">
        이전 실행이 적어 아직 비교하지 않습니다 (같은 케이스·환경의 이전 실행 {drift.observations}회)
      </p>
    );
  }
  if (drift.changes.length === 0) {
    return (
      <div className="space-y-2">
        <p className="text-xs text-muted-foreground">이전 실행 {drift.observations}회와 구조가 같습니다</p>
        <ApprovalNote drift={drift} />
      </div>
    );
  }
  return (
    <div className="space-y-2">
      <p className="text-xs text-muted-foreground">이전 실행 {drift.observations}회를 기준으로 비교한 변화입니다</p>
      {drift.pending && (
        <div className="flex flex-wrap items-center gap-3 rounded-md bg-amber-500/10 p-3 text-sm text-amber-800 dark:text-amber-300">
          <p className="min-w-0 flex-1">
            승인 대기: 기준선에 반영하기 전까지 매 실행 같은 변화를 보고합니다. 의도한 API 변경이면 기준선에 반영해
            주세요
          </p>
          {approve && <ApproveSchemaButton action={approve} />}
        </div>
      )}
      <ApprovalNote drift={drift} />
      <ul aria-label="구조 변화" className="divide-y rounded-md border">
        {drift.changes.map((change) => (
          <li key={`${change.kind} ${change.path}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2">
            <Badge className={levelStyle[change.level].className}>{levelStyle[change.level].label}</Badge>
            <span className="text-sm">{kindLabel[change.kind]}</span>
            <code className="font-mono text-xs break-all">{change.path}</code>
            <span className="ml-auto font-mono text-xs text-muted-foreground">
              {types(change.expected)} → {types(change.actual)}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** 승인 대기였다가 기준선에 반영한 기록. 승인한 사용자를 찾을 수 없으면 시각만 */
function ApprovalNote({ drift }: { drift: ResultDrift }) {
  if (!drift.approval) {
    return null;
  }
  const { approvedBy, approvedAt } = drift.approval;
  return (
    <p className="text-xs text-muted-foreground">
      기준선에 반영됨 · {approvedBy && `${approvedBy.name} · `}
      {formatDateTime(approvedAt)}
    </p>
  );
}

function types(values: string[]): string {
  return values.length === 0 ? "없음" : values.join(" | ");
}
