import { Badge } from "@/components/ui/badge";
import type { MaintenanceWindow } from "@/lib/api/types";
import { formatEnd, formatPeriod } from "@/lib/maintenance";

/** 환경의 점검 상태. 점검 중이면 끝 시각, 예정이면 기간 */
export function MaintenanceBadge({ window, now }: { window: MaintenanceWindow; now: Date }) {
  if (window.active) {
    return (
      <Badge className="bg-amber-500/10 text-amber-700 dark:text-amber-400">
        점검 중 ~{formatEnd(window.endsAt, now)}
      </Badge>
    );
  }
  return <Badge variant="outline">점검 예정 {formatPeriod(window.startsAt, window.endsAt)}</Badge>;
}
