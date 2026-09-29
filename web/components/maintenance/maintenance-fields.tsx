import { TextField } from "@/components/catalog/field";
import type { MaintenanceWindow } from "@/lib/api/types";
import { MAX_DAYS, MAX_REASON_LENGTH, toKstInput } from "@/lib/maintenance";

/**
 * 점검 시간 등록·수정 입력 항목 (KST). 등록은 지금부터 1시간을 기본값으로 채움
 * @param now 기본값 기준 시각 (서버 렌더링 시각)
 */
export function MaintenanceFields({ window, now }: { window?: MaintenanceWindow; now: Date }) {
  const startsAt = window ? toKstInput(new Date(window.startsAt)) : toKstInput(now);
  const endsAt = window ? toKstInput(new Date(window.endsAt)) : toKstInput(new Date(now.getTime() + 60 * 60 * 1000));
  return (
    <>
      <TextField name="startsAt" label="시작 (KST)" type="datetime-local" defaultValue={startsAt} required />
      <TextField
        name="endsAt"
        label="끝 (KST)"
        type="datetime-local"
        defaultValue={endsAt}
        required
        hint={`최대 ${MAX_DAYS}일. 끝나면 스케줄이 따로 조치 없이 다시 실행됩니다`}
      />
      <TextField
        name="reason"
        label="사유"
        defaultValue={window?.reason}
        maxLength={MAX_REASON_LENGTH}
        placeholder="GW 배포"
      />
    </>
  );
}
