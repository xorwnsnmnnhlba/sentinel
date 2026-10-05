import type { NotificationDeliveryItem, NotificationTone } from "@/lib/api/types";
import { failureReason } from "@/lib/webhook";

/** 알림 발송 이력 표시 문구 (api/README.md "발송 이력") */

export const TONE_LABELS: Record<NotificationTone, string> = {
  FAILURE: "실패",
  WARNING: "주의",
  SUCCESS: "복구",
  INFO: "안내",
};

const FORMAT_LABELS: Record<NotificationDeliveryItem["format"], string> = {
  SLACK: "Slack",
  TEAMS: "Teams",
  TEXT: "텍스트",
  LOG: "로그",
};

/** 보낸 곳. 예: "billing · Slack", "기본 채널 · Teams" */
export function channelLabel(item: Pick<NotificationDeliveryItem, "project" | "format">): string {
  return `${item.project?.name ?? "기본 채널"} · ${FORMAT_LABELS[item.format]}`;
}

/** 결과 한마디. 예: "보냄", "로그에만 기록", "HTTP 404", "연결하지 못했습니다" */
export function resultLabel(item: Pick<NotificationDeliveryItem, "delivered" | "format" | "statusCode" | "error">) {
  if (item.delivered) {
    return item.format === "LOG" ? "로그에만 기록" : "보냄";
  }
  return item.statusCode !== null ? `HTTP ${item.statusCode}` : failureReason(item.error);
}

/**
 * 결과 아래 짧은 확인 안내. 보낸 알림은 없음, 로그 기록은 기본 채널 설정 안내
 */
export function resultHint(
  item: Pick<NotificationDeliveryItem, "delivered" | "format" | "statusCode" | "error">,
): string | null {
  if (item.delivered) {
    return item.format === "LOG" ? "기본 채널 주소(SENTINEL_WEBHOOK_URL) 미설정" : null;
  }
  const status = item.statusCode;
  if (status === null) {
    switch (item.error) {
      case "TIMEOUT":
        return "채널 응답이 늦음";
      case "CONNECTION_FAILED":
        return "사내망·프록시에서 채널 주소로 나갈 수 있는지 확인";
      default:
        return null;
    }
  }
  if (status === 404 || status === 410) {
    return "Webhook이 삭제되었거나 주소가 바뀜";
  }
  if (status === 401 || status === 403) {
    return "채널이 거부함 (권한·토큰 확인)";
  }
  if (status === 429) {
    return "채널의 발송 한도 초과";
  }
  return status >= 500 ? "채널 서버 오류" : "주소와 채널 설정 확인";
}

/** 알림 이력 화면 주소. 프로젝트 Webhook으로 보낸 것만 보려면 projectId */
export function notificationsHref(projectId?: number): string {
  return projectId === undefined ? "/notifications" : `/notifications?projectId=${projectId}`;
}
