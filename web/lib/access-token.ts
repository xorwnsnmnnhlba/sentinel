/** API 토큰 유효 기간(일). 서버와 같은 선택지 (만료 없는 토큰 없음) */
export const VALIDITY_DAYS = [30, 90, 180, 365] as const;

export const DEFAULT_VALIDITY_DAYS = 90;

export const MAX_TOKEN_NAME_LENGTH = 100;

/**
 * CI에서 실행할 명령 예시 (scripts/sentinel-run.sh). 토큰은 CI 비밀 값으로 넣도록 변수 이름만
 * @param siteUrl Sentinel 접속 주소
 */
export function runCommand(siteUrl: string): string {
  return [
    `SENTINEL_URL=${siteUrl}`,
    "SENTINEL_TOKEN=$SENTINEL_TOKEN",
    "scripts/sentinel-run.sh --suite <스위트 ID> --environment <환경 ID>",
  ].join(" \\\n  ");
}
