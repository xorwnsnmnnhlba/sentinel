import type { SecretState, SystemStatus, UndecryptableSecret } from "@/lib/api/types";

/** 암호화 키 상태 문구. 꺼짐·교체 중은 할 일과 함께 */
export function secretStateText(state: SecretState): { label: string; tone: "ok" | "warning"; note: string | null } {
  switch (state) {
    case "ENABLED":
      return { label: "사용 중", tone: "ok", note: null };
    case "ROTATING":
      return {
        label: "키 교체 중",
        tone: "warning",
        note: "이전 키로 저장된 값은 기동 시 새 키로 다시 암호화됨. 아래에 풀 수 없는 값이 없으면 sentinel.env에서 SENTINEL_SECRET_KEY_PREVIOUS 줄 삭제 후 재기동",
      };
    case "DISABLED":
      return {
        label: "꺼짐",
        tone: "warning",
        note: "SENTINEL_SECRET_KEY가 없어 환경 시크릿과 프로젝트별 알림 Webhook을 등록할 수 없음",
      };
  }
}

/** 풀 수 없는 값의 위치. 예: "프로젝트 Webhook 주소", "환경 dev · 시크릿 token" */
export function undecryptableLocation(item: UndecryptableSecret): string {
  return item.environment ? `환경 ${item.environment.name} · 시크릿 ${item.secret}` : "프로젝트 Webhook 주소";
}

export interface SettingRow {
  name: string;
  variable: string;
  configured: boolean;
  /** 지정하지 않았을 때 생기는 일 */
  whenMissing: string;
}

/** 알림·감시 관련 서버 설정의 지정 여부 (값은 표시하지 않음) */
export function settingRows(status: SystemStatus): SettingRow[] {
  return [
    {
      name: "기본 알림 채널",
      variable: "SENTINEL_WEBHOOK_URL",
      configured: status.defaultChannel,
      whenMissing: "Webhook을 지정하지 않은 프로젝트와 스케줄러 알림이 서버 로그에만 남음",
    },
    {
      name: "결과 링크 주소",
      variable: "SENTINEL_WEB_URL",
      configured: status.resultLinks,
      whenMissing: "알림에 결과 화면 링크가 붙지 않음",
    },
    {
      name: "외부 생존 신호",
      variable: "SENTINEL_HEARTBEAT_URL",
      configured: status.heartbeat,
      whenMissing: "Sentinel이 멈춰도 다시 켜질 때까지 알 수 없음",
    },
  ];
}
