/** 서버·브라우저 시간대와 무관하게 한국 시각으로 표시 */
const dateTime = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hour12: false,
});

/** YYYY-MM-DD HH:mm:ss (KST) */
export function formatDateTime(iso: string): string {
  const parts = Object.fromEntries(
    dateTime.formatToParts(new Date(iso)).map((part) => [part.type, part.value]),
  ) as Record<Intl.DateTimeFormatPartTypes, string>;
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
}

export function formatElapsed(ms: number | null | undefined): string {
  if (ms == null) {
    return "-";
  }
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
}

export function formatRunDuration(startedAt: string, finishedAt: string | null): string {
  return finishedAt ? formatElapsed(new Date(finishedAt).getTime() - new Date(startedAt).getTime()) : "-";
}

/** JSON이면 들여쓰기, 아니면 원문 */
export function prettyBody(body: string | null | undefined): string {
  if (!body) {
    return "";
  }
  try {
    return JSON.stringify(JSON.parse(body), null, 2);
  } catch {
    return body;
  }
}

/** 진행률 문구. 예: "12건 중 5건", 계획 수를 모르면 "5건" */
export function formatProgress(done: number, planned: number | null): string {
  return planned === null ? `${done}건` : `${planned}건 중 ${done}건`;
}
