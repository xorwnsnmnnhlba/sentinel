import type { ErrorReason } from "@/lib/api/types";

/**
 * 결과의 오류 사유(응답을 받지 못했거나 보내기 전에 실패한 이유) 표시 문구와 확인할 점 (api/README.md "결과 해석")
 * 이름은 대시보드 실패 원인(lib/failure-cause.ts)과 같은 표기
 */

export interface ErrorReasonText {
  label: string;
  hint: string;
}

export const ERROR_REASONS: Record<ErrorReason, ErrorReasonText> = {
  UNRESOLVED_VARIABLE: {
    label: "변수 값 없음",
    hint: "요청을 보내지 않았습니다. 환경의 변수·시크릿 이름과 앞 케이스의 값 추출을 확인해 주세요",
  },
  INVALID_REQUEST: {
    label: "잘못된 요청",
    hint: "요청을 보내지 않았습니다. 경로·헤더와 변수 값에 공백 등 주소에 쓸 수 없는 문자가 있는지 확인해 주세요",
  },
  TIMEOUT: {
    label: "타임아웃",
    hint: "응답을 기다리다 제한 시간이 지났습니다. 대상 서버·GW 상태를 확인해 주세요",
  },
  CONNECTION_FAILED: {
    label: "연결 실패",
    hint: "서버에 연결하지 못했습니다. 환경의 기본 URL, 서버 기동 여부, 방화벽을 확인해 주세요",
  },
  IO_ERROR: {
    label: "연결 끊김",
    hint: "주고받는 중 연결이 끊겼습니다. 대상 서버·GW 상태를 확인해 주세요",
  },
  TLS_ERROR: {
    label: "인증서·TLS 오류",
    hint: "대상 서버 인증서의 만료·발급자·이름을 확인해 주세요. 다시 보내도 같아 재시도하지 않습니다",
  },
  BLOCKED_DESTINATION: {
    label: "막힌 목적지",
    hint: "링크 로컬·클라우드 메타데이터 주소라 보내지 않았습니다. 환경의 기본 URL을 확인해 주세요",
  },
  UNEXPECTED: {
    label: "Sentinel 내부 오류",
    hint: "관리자에게 문의해 주세요. 자세한 내용은 서버 로그에 있습니다",
  },
};

/** 모르는 사유(서버가 먼저 업데이트된 경우 등)는 코드를 이름으로, 확인할 점 없이 */
export function describeErrorReason(reason: string): ErrorReasonText {
  return Object.hasOwn(ERROR_REASONS, reason) ? ERROR_REASONS[reason as ErrorReason] : { label: reason, hint: "" };
}
