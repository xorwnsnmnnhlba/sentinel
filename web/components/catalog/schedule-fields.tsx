import { TextField } from "@/components/catalog/field";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import type { EnvironmentItem, ScheduleDetail } from "@/lib/api/types";

/**
 * 스케줄 등록·수정 입력 항목. 수정 시 현재 환경이 선택된 상태로 표시되며 같은 프로젝트의 환경으로 변경 가능
 * 휴일 건너뛰기는 기본 꺼짐 (24시간 감시 스케줄은 휴일에도 실행)
 */
export function ScheduleFields({
  environments,
  schedule,
}: {
  environments: EnvironmentItem[];
  schedule?: ScheduleDetail;
}) {
  return (
    <>
      {environments.length > 0 && (
        <div className="space-y-2">
          <Label htmlFor="schedule-environment">실행 환경</Label>
          <NativeSelect
            id="schedule-environment"
            name="environmentId"
            className="w-full"
            defaultValue={schedule?.environment.id}
            required
          >
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
      <div className="space-y-1">
        <label className="flex items-center gap-2 text-sm font-medium">
          <input
            type="checkbox"
            name="skipHolidays"
            value="true"
            defaultChecked={schedule?.skipHolidays ?? false}
            aria-describedby="schedule-skip-holidays-hint"
            className="size-4 accent-primary"
          />
          휴일 건너뛰기
        </label>
        <p id="schedule-skip-holidays-hint" className="text-xs text-muted-foreground">
          휴일 화면에 등록한 날에는 실행하지 않음
        </p>
      </div>
    </>
  );
}
