import Link from "next/link";

import { Panel, PanelEmpty, PanelFooter } from "@/components/catalog/section";
import { DeliveryResultBadge, ToneBadge } from "@/components/notification/delivery-badges";
import { TestNotificationButton } from "@/components/notification/test-notification-button";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { sendDefaultTestNotification } from "@/lib/actions/catalog";
import { apiGet } from "@/lib/api/server";
import type {
  AuthenticatedUser,
  NotificationChannel,
  NotificationDeliveryItem,
  Page,
  ProjectItem,
} from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";
import { channelLabel, defaultChannelConfigured, resultHint } from "@/lib/notification-delivery";

const PAGE_SIZE = 20;

export const metadata = { title: "알림 이력 · Sentinel" };

type Filters = { projectId?: string; failed?: "true" };

/**
 * 알림 발송 이력. "알림이 안 왔다"를 서버 로그 없이 확인 (모든 사용자). 주소·본문은 기록하지 않음
 * 프로젝트 필터는 그 프로젝트 Webhook으로 보낸 것만 (기본 채널로 간 알림은 프로젝트로 나누지 않음)
 */
export default async function NotificationsPage(props: PageProps<"/notifications">) {
  const searchParams = await props.searchParams;
  const page = Math.max(Number(single(searchParams.page)) || 0, 0);
  const projectId = single(searchParams.projectId);
  const filters: Filters = {
    projectId: projectId && /^\d+$/.test(projectId) ? projectId : undefined,
    failed: single(searchParams.failed) === "true" ? "true" : undefined,
  };
  const hasFilter = filters.projectId !== undefined || filters.failed !== undefined;

  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  appendFilters(query, filters);
  const [deliveries, projects, me, channels] = await Promise.all([
    apiGet<Page<NotificationDeliveryItem>>(`/api/notification-deliveries?${query}`),
    apiGet<ProjectItem[]>("/api/projects"),
    apiGet<AuthenticatedUser>("/api/sessions/current"),
    apiGet<NotificationChannel[]>("/api/notification-channels"),
  ]);

  return (
    <div className="space-y-4">
      <PageHeader title="알림 이력" />

      {/* 기본 채널은 모든 프로젝트에 걸친 서버 설정이라 관리자에게만. 배포 직후 받는지 확인용 */}
      {me.role === "ADMIN" && (
        <Panel title="기본 알림 채널" meta="SENTINEL_WEBHOOK_URL">
          <div className="space-y-3 p-4 text-sm">
            {defaultChannelConfigured(channels) ? (
              <>
                <p>
                  Webhook을 지정하지 않은 프로젝트의 실패·복구 알림과 DB 연결 실패·모니터링 공백·주간 요약을 보냅니다
                </p>
                <TestNotificationButton label="기본 채널 테스트 알림" action={sendDefaultTestNotification} />
              </>
            ) : (
              <p className="text-muted-foreground">
                주소가 설정되지 않아 이 채널 알림은 서버 로그에만 남습니다. 서버의 sentinel.env에 지정한 뒤 api를 다시
                시작해 주세요
              </p>
            )}
          </div>
        </Panel>
      )}

      <form
        method="get"
        action="/notifications"
        aria-label="알림 이력 필터"
        className="flex flex-wrap items-end gap-3 rounded-lg border bg-card p-4"
      >
        <div className="space-y-1.5">
          <Label htmlFor="filter-project" className="text-xs text-muted-foreground">
            프로젝트 Webhook
          </Label>
          <NativeSelect id="filter-project" name="projectId" defaultValue={filters.projectId ?? ""} className="w-44">
            <NativeSelectOption value="">전체 (기본 채널 포함)</NativeSelectOption>
            {projects.map((project) => (
              <NativeSelectOption key={project.id} value={project.id}>
                {project.name}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </div>
        <label className="flex h-8 items-center gap-2 text-sm">
          <input
            type="checkbox"
            name="failed"
            value="true"
            defaultChecked={filters.failed === "true"}
            className="size-4 accent-primary"
          />
          보내지 못한 것만
        </label>
        <div className="flex gap-2">
          <Button type="submit">조회</Button>
          {hasFilter && (
            <Button asChild variant="outline">
              <Link href="/notifications">초기화</Link>
            </Button>
          )}
        </div>
      </form>

      <Panel
        title="발송"
        meta={`${hasFilter ? "조건에 맞는 알림" : "전체"} ${deliveries.totalElements}건 · 최신순 · 90일 보관`}
      >
        {deliveries.content.length === 0 ? (
          <PanelEmpty message={hasFilter ? "조건에 맞는 알림이 없습니다" : "보낸 알림이 없습니다"} />
        ) : (
          <Table aria-label="알림 발송 이력">
            <TableHeader>
              <TableRow>
                <TableHead>알림 · 시각 (KST) · 보낸 곳</TableHead>
                <TableHead className="w-[40%] sm:w-56">결과</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {deliveries.content.map((item) => {
                const hint = resultHint(item);
                return (
                  <TableRow key={item.id}>
                    {/* 좁은 화면에서도 제목이 눌리지 않도록 시각·보낸 곳은 제목 아래 한 줄로 */}
                    <TableCell className="whitespace-normal">
                      <div className="flex items-start gap-2">
                        <ToneBadge tone={item.tone} />
                        <span className="font-medium break-words">{item.title}</span>
                      </div>
                      <div className="mt-1 text-xs text-muted-foreground">
                        <span className="font-mono">{formatDateTime(item.sentAt)}</span> · {channelLabel(item)}
                      </div>
                    </TableCell>
                    <TableCell className="whitespace-normal">
                      <DeliveryResultBadge item={item} />
                      {hint && <div className="mt-1 text-xs wrap-anywhere text-muted-foreground">{hint}</div>}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
        {deliveries.totalPages > 1 && (
          <PanelFooter>
            <span className="font-mono text-xs text-muted-foreground">
              {deliveries.page + 1} / {deliveries.totalPages} 페이지
            </span>
            <div className="flex gap-2">
              <PageLink page={deliveries.page - 1} filters={filters} disabled={deliveries.page === 0} label="이전" />
              <PageLink
                page={deliveries.page + 1}
                filters={filters}
                disabled={deliveries.page + 1 >= deliveries.totalPages}
                label="다음"
              />
            </div>
          </PanelFooter>
        )}
      </Panel>
    </div>
  );
}

function single(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

function appendFilters(query: URLSearchParams, filters: Filters) {
  Object.entries(filters).forEach(([name, value]) => {
    if (value) {
      query.set(name, value);
    }
  });
}

/** 페이지를 넘겨도 필터가 풀리지 않도록 조건을 그대로 이어붙임 */
function PageLink({
  page,
  filters,
  disabled,
  label,
}: {
  page: number;
  filters: Filters;
  disabled: boolean;
  label: string;
}) {
  if (disabled) {
    return (
      <Button size="sm" variant="outline" disabled>
        {label}
      </Button>
    );
  }
  const query = new URLSearchParams({ page: String(page) });
  appendFilters(query, filters);
  return (
    <Button asChild size="sm" variant="outline">
      <Link href={`/notifications?${query}`}>{label}</Link>
    </Button>
  );
}
