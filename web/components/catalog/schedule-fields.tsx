import { TextField } from "@/components/catalog/field";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import type { EnvironmentItem, ScheduleDetail } from "@/lib/api/types";

/**
 * 스케줄 등록·수정 입력 항목. 등록 시에만 환경 선택 (환경 변경은 미지원, 삭제 후 재등록)
 */
export function ScheduleFields({
  environments,
  schedule,
}: {
  environments?: EnvironmentItem[];
  schedule?: ScheduleDetail;
}) {
  return (
    <>
      {environments && (
        <div className="space-y-2">
          <Label htmlFor="schedule-environment">실행 환경</Label>
          <NativeSelect id="schedule-environment" name="environmentId" className="w-full" required>
            {environments.map((environment) => (
              <NativeSelectOption key={environment.id} value={environment.id}>
                {environment.name}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </div>
      )}
      <TextField
        name="cron"
        label="cron"
        defaultValue={schedule?.cron ?? "10 9 * * 1-5"}
        required
        maxLength={100}
        placeholder="10 9 * * 1-5"
        hint="분 시 일 월 요일 (5자리), Asia/Seoul 기준. 예: 10 9 * * 1-5 → 평일 09:10, 0 */2 * * * → 2시간마다"
      />
    </>
  );
}
