import Link from "next/link";

import { Panel, PanelEmpty } from "@/components/catalog/section";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { apiGet } from "@/lib/api/server";
import type { SystemStatus } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";
import { secretStateText, settingRows, undecryptableLocation } from "@/lib/system-status";

export const metadata = { title: "시스템 상태 · Sentinel" };

/**
 * 시스템 상태 (관리자). 배포·업데이트·키 교체 뒤 서버 로그 대신 확인 (docs/deploy.md).
 * 일반 사용자는 Spring이 403으로 응답하여 오류 화면 표시
 */
export default async function SystemStatusPage() {
  const status = await apiGet<SystemStatus>("/api/system-status");
  const secret = secretStateText(status.secrets.state);
  const undecryptable = status.secrets.undecryptable;

  return (
    <div className="space-y-4">
      <PageHeader title="시스템 상태" />

      <Panel title="버전">
        <dl className="grid gap-x-6 gap-y-3 px-4 py-3 text-sm sm:grid-cols-4">
          <Item label="버전">{status.build?.version ?? "알 수 없음"}</Item>
          <Item label="빌드 (KST)">{status.build ? formatDateTime(status.build.builtAt) : "알 수 없음"}</Item>
          <Item label="기동 (KST)">{status.startedAt ? formatDateTime(status.startedAt) : "기동 중"}</Item>
          <Item label="DB 마이그레이션">{status.databaseMigration ? `V${status.databaseMigration}` : "없음"}</Item>
        </dl>
      </Panel>

      <Panel
        title="암호화 키"
        meta="SENTINEL_SECRET_KEY"
        action={<Badge variant={secret.tone === "ok" ? "secondary" : "destructive"}>{secret.label}</Badge>}
      >
        {secret.note && <p className="border-b px-4 py-3 text-sm text-muted-foreground">{secret.note}</p>}
        {status.secrets.state !== "DISABLED" &&
          (undecryptable.length === 0 ? (
            <PanelEmpty message="풀 수 없는 값 없음" />
          ) : (
            <Table aria-label="풀 수 없는 값">
              <TableHeader>
                <TableRow>
                  <TableHead>프로젝트</TableHead>
                  <TableHead>위치</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {undecryptable.map((item) => (
                  <TableRow key={`${item.project.id}-${item.environment?.id ?? "webhook"}-${item.secret ?? ""}`}>
                    <TableCell>
                      <Link href={`/projects/${item.project.id}`} className="text-brand hover:underline">
                        {item.project.name}
                      </Link>
                    </TableCell>
                    <TableCell className="font-mono text-xs">{undecryptableLocation(item)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ))}
        {undecryptable.length > 0 && (
          <p className="border-t px-4 py-3 text-xs text-muted-foreground">
            키가 바뀌어 지금 키로 풀 수 없는 값. 프로젝트 화면에서 다시 입력하거나, 저장할 때의 키를
            SENTINEL_SECRET_KEY_PREVIOUS로 지정해 재기동
          </p>
        )}
      </Panel>

      <Panel title="알림·감시 설정" meta="sentinel.env">
        <Table aria-label="알림·감시 설정">
          <TableHeader>
            <TableRow>
              <TableHead className="w-1/3">항목</TableHead>
              <TableHead>상태</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {settingRows(status).map((row) => (
              <TableRow key={row.variable}>
                <TableCell className="align-top">
                  <div className="font-medium">{row.name}</div>
                  <div className="font-mono text-xs text-muted-foreground">{row.variable}</div>
                </TableCell>
                <TableCell className="align-top whitespace-normal">
                  <Badge variant={row.configured ? "secondary" : "outline"}>{row.configured ? "설정됨" : "없음"}</Badge>
                  {!row.configured && <p className="mt-1 text-xs text-muted-foreground">{row.whenMissing}</p>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Panel>

      <Panel title="보관 기간">
        <dl className="grid gap-x-6 gap-y-3 px-4 py-3 text-sm sm:grid-cols-4">
          <Item label="결과 원문">{status.retention.rawResultDays}일</Item>
          <Item label="알림 발송 이력">{status.retention.notificationDeliveryDays}일</Item>
        </dl>
      </Panel>
    </div>
  );
}

function Item({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 font-mono">{children}</dd>
    </div>
  );
}
