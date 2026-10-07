import type { EnvironmentItem, ProjectItem, ScheduleDetail, SuiteItem } from "@/lib/api/types";

/** 전체 스케줄 화면의 행 구성·정렬·필터 (스케줄 응답에 이름이 없어 목록 API와 이어 붙임) */

export interface ScheduleRow {
  schedule: ScheduleDetail;
  project: { id: number; name: string };
  suite: { id: number; name: string };
  environmentName: string;
}

/** 상태 필터. 주소 값 그대로 */
export type ScheduleStatusFilter = "enabled" | "disabled" | undefined;

/** 한 번에 바꿀 수 있는 스케줄 수 (Spring과 일치) */
export const MAX_BULK_UPDATE = 200;

/**
 * 스케줄에 프로젝트·스위트·환경 이름을 붙임. 스위트·프로젝트를 찾을 수 없는 스케줄(그사이 삭제)은 제외
 * 정렬: 켜진 스케줄은 다음 실행이 가까운 순, 꺼진 스케줄은 그 뒤에 프로젝트·스위트 이름 순
 */
export function scheduleRows(
  schedules: ScheduleDetail[],
  projects: ProjectItem[],
  suites: SuiteItem[],
  environments: EnvironmentItem[],
): ScheduleRow[] {
  const projectById = new Map(projects.map((project) => [project.id, project]));
  const suiteById = new Map(suites.map((suite) => [suite.id, suite]));
  const environmentById = new Map(environments.map((environment) => [environment.id, environment]));
  const rows: ScheduleRow[] = [];
  for (const schedule of schedules) {
    const suite = suiteById.get(schedule.suiteId);
    const project = suite && projectById.get(suite.projectId);
    if (!suite || !project) {
      continue;
    }
    rows.push({
      schedule,
      project: { id: project.id, name: project.name },
      suite: { id: suite.id, name: suite.name },
      environmentName: environmentById.get(schedule.environmentId)?.name ?? "-",
    });
  }
  return rows.sort(compareRows);
}

function compareRows(a: ScheduleRow, b: ScheduleRow): number {
  const aNext = a.schedule.enabled ? a.schedule.nextRunAt : null;
  const bNext = b.schedule.enabled ? b.schedule.nextRunAt : null;
  if (aNext !== null && bNext !== null) {
    return aNext.localeCompare(bNext) || a.schedule.id - b.schedule.id;
  }
  if (aNext !== null || bNext !== null) {
    return aNext !== null ? -1 : 1;
  }
  return (
    a.project.name.localeCompare(b.project.name, "ko") ||
    a.suite.name.localeCompare(b.suite.name, "ko") ||
    a.schedule.id - b.schedule.id
  );
}

/** 프로젝트·상태로 좁힘. 지정하지 않은 조건은 무시 */
export function filterRows(
  rows: ScheduleRow[],
  filters: { projectId?: number; status?: ScheduleStatusFilter },
): ScheduleRow[] {
  return rows.filter(
    (row) =>
      (filters.projectId === undefined || row.project.id === filters.projectId) &&
      (filters.status === undefined || row.schedule.enabled === (filters.status === "enabled")),
  );
}

/** 주소의 상태 값. 모르는 값은 전체 */
export function parseStatus(value: string | undefined): ScheduleStatusFilter {
  return value === "enabled" || value === "disabled" ? value : undefined;
}
