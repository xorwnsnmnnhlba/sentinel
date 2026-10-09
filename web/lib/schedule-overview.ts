import type { ScheduleDetail } from "@/lib/api/types";

/** 전체 스케줄 화면의 정렬·필터 */

/** 상태 필터. 주소 값 그대로 */
export type ScheduleStatusFilter = "enabled" | "disabled" | undefined;

/** 한 번에 바꿀 수 있는 스케줄 수 (Spring과 일치) */
export const MAX_BULK_UPDATE = 200;

/** 켜진 스케줄은 다음 실행이 가까운 순, 꺼진 스케줄은 그 뒤에 프로젝트·스위트 이름 순 */
export function sortSchedules(schedules: ScheduleDetail[]): ScheduleDetail[] {
  return [...schedules].sort(compareSchedules);
}

function compareSchedules(a: ScheduleDetail, b: ScheduleDetail): number {
  const aNext = a.enabled ? a.nextRunAt : null;
  const bNext = b.enabled ? b.nextRunAt : null;
  if (aNext !== null && bNext !== null) {
    return aNext.localeCompare(bNext) || a.id - b.id;
  }
  if (aNext !== null || bNext !== null) {
    return aNext !== null ? -1 : 1;
  }
  return (
    a.project.name.localeCompare(b.project.name, "ko") || a.suite.name.localeCompare(b.suite.name, "ko") || a.id - b.id
  );
}

/** 프로젝트·상태로 좁힘. 지정하지 않은 조건은 무시 */
export function filterSchedules(
  schedules: ScheduleDetail[],
  filters: { projectId?: number; status?: ScheduleStatusFilter },
): ScheduleDetail[] {
  return schedules.filter(
    (schedule) =>
      (filters.projectId === undefined || schedule.project.id === filters.projectId) &&
      (filters.status === undefined || schedule.enabled === (filters.status === "enabled")),
  );
}

/** 주소의 상태 값. 모르는 값은 전체 */
export function parseStatus(value: string | undefined): ScheduleStatusFilter {
  return value === "enabled" || value === "disabled" ? value : undefined;
}
