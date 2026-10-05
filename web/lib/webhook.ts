/**
 * 프로젝트 알림 채널(Webhook) 입력 확인과 연결 확인 결과 문구 (api/README.md "프로젝트별 채널")
 */

export const MAX_WEBHOOK_URL_LENGTH = 1000;

/** 연결 확인 알림 결과. statusCode는 받은 응답, error는 응답을 받지 못한 사유 */
export interface Delivery {
  delivered: boolean;
  statusCode: number | null;
  error: string | null;
}

/** 서버와 같은 기준(http(s) 절대 주소, 1000자 이하)으로 먼저 확인하여 한국어로 안내. 문제가 없으면 null */
export function webhookUrlError(value: string): string | null {
  const url = value.trim();
  if (url === "") {
    return "Webhook 주소를 입력해 주세요";
  }
  if (url.length > MAX_WEBHOOK_URL_LENGTH) {
    return `Webhook 주소는 ${MAX_WEBHOOK_URL_LENGTH}자 이하입니다`;
  }
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return "http:// 또는 https://로 시작하는 주소를 입력해 주세요";
  }
  return parsed.protocol === "http:" || parsed.protocol === "https:"
    ? null
    : "http:// 또는 https://로 시작하는 주소를 입력해 주세요";
}

const ERROR_LABELS: Record<string, string> = {
  CONNECTION_FAILED: "연결하지 못했습니다",
  TIMEOUT: "응답 대기 시간이 지났습니다",
  IO_ERROR: "전송 중 연결이 끊겼습니다",
  BLOCKED_DESTINATION: "내부·메타데이터 주소라 보내지 않았습니다",
  INVALID_URL: "주소 형식이 올바르지 않습니다",
};

/** 연결 확인 결과 문구 */
export function deliveryMessage(delivery: Delivery): string {
  if (delivery.delivered) {
    return `테스트 알림을 보냈습니다 (HTTP ${delivery.statusCode}). 채널에 메시지가 왔는지 확인해 주세요`;
  }
  if (delivery.statusCode !== null) {
    return `채널이 받지 않았습니다 (HTTP ${delivery.statusCode}). 주소와 채널 설정을 확인해 주세요`;
  }
  return `테스트 알림을 보내지 못했습니다: ${failureReason(delivery.error)}`;
}

/** 응답을 받지 못한 사유 문구. 모르는 값은 그대로 */
export function failureReason(error: string | null): string {
  return error ? (ERROR_LABELS[error] ?? error) : "알 수 없는 이유";
}
