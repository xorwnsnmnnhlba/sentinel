import Link from "next/link";

import { Panel, PanelEmpty } from "@/components/catalog/section";
import { ScheduleBulkTable } from "@/components/catalog/schedule-bulk-table";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { updateSchedules } from "@/lib/actions/catalog";
import { apiGet } from "@/lib/api/server";
import type { ProjectItem, ScheduleDetail } from "@/lib/api/types";
import { filterSchedules, parseStatus, sortSchedules } from "@/lib/schedule-overview";

export const metadata = { title: "스케줄 · Sentinel" };

/**
 * 모든 프로젝트의 스케줄. 다음 실행이 가까운 순, 꺼진 스케줄은 뒤. 골라서 켜기·끄기·휴일 건너뛰기를 한 번에
 * 스케줄 등록·cron 수정은 스위트 화면에서
 */
export default async function SchedulesPage(props: PageProps<"/schedules">) {
  const searchParams = await props.searchParams;
  const projectParam = single(searchParams.projectId);
  const projectId = projectParam && /^\d+$/.test(projectParam) ? Number(projectParam) : undefined;
  const status = parseStatus(single(searchParams.status));
  const hasFilter = projectId !== undefined || status !== undefined;

  // 프로젝트 목록은 필터 선택지용 (스케줄 응답에 프로젝트·스위트·환경 이름이 있음)
  const [all, projects] = await Promise.all([
    apiGet<ScheduleDetail[]>("/api/schedules"),
    apiGet<ProjectItem[]>("/api/projects"),
  ]);
  const rows = filterSchedules(sortSchedules(all), { projectId, status });
  const disabled = all.filter((schedule) => !schedule.enabled).length;

  return (
    <div className="space-y-4">
      <PageHeader title="스케줄" />

      <form
        method="get"
        action="/schedules"
        aria-label="스케줄 필터"
        className="flex flex-wrap items-end gap-3 rounded-lg border bg-card p-4"
      >
        <div className="space-y-1.5">
          <Label htmlFor="filter-project" className="text-xs text-muted-foreground">
            프로젝트
          </Label>
          <NativeSelect id="filter-project" name="projectId" defaultValue={projectId ?? ""} className="w-44">
            <NativeSelectOption value="">전체</NativeSelectOption>
            {projects.map((project) => (
              <NativeSelectOption key={project.id} value={project.id}>
                {project.name}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="filter-status" className="text-xs text-muted-foreground">
            상태
          </Label>
          <NativeSelect id="filter-status" name="status" defaultValue={status ?? ""} className="w-32">
            <NativeSelectOption value="">전체</NativeSelectOption>
            <NativeSelectOption value="enabled">활성</NativeSelectOption>
            <NativeSelectOption value="disabled">비활성</NativeSelectOption>
          </NativeSelect>
        </div>
        <div className="flex gap-2">
          <Button type="submit">조회</Button>
          {hasFilter && (
            <Button asChild variant="outline">
              <Link href="/schedules">초기화</Link>
            </Button>
          )}
        </div>
      </form>

      <Panel
        title="스케줄"
        count={rows.length}
        meta={`다음 실행 순 · Asia/Seoul${disabled > 0 ? ` · 비활성 ${disabled}개` : ""}`}
      >
        {rows.length === 0 ? (
          <PanelEmpty
            message={
              hasFilter ? "조건에 맞는 스케줄이 없습니다" : "등록된 스케줄이 없습니다. 스위트 화면에서 추가해 주세요"
            }
          />
        ) : (
          <ScheduleBulkTable schedules={rows} action={updateSchedules} />
        )}
      </Panel>
    </div>
  );
}

function single(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}
