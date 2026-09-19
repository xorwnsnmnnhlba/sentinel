import "server-only";

/**
 * Spring API 서버 전용 클라이언트. 브라우저 번들 포함 시 빌드 실패(server-only).
 * Spring 주소는 서버 전용 환경변수. NEXT_PUBLIC_ 접두어 사용 시 브라우저에 내부 주소 노출
 */
export const apiBaseUrl = process.env.INTERNAL_API_URL ?? "http://localhost:8080";

/** Spring ProblemDetail 응답을 담는 오류 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly detail: string,
  ) {
    super(`API ${status}: ${detail}`);
  }
}

/** 최신 실행 결과를 보여주는 도구이므로 캐시 미사용 */
export async function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  return fetch(new URL(path, apiBaseUrl), { ...init, cache: "no-store" });
}

export async function apiGet<T>(path: string): Promise<T> {
  const response = await apiFetch(path, { headers: { Accept: "application/json" } });
  if (!response.ok) {
    throw await toApiError(response);
  }
  return (await response.json()) as T;
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const problem = (await response.json()) as { detail?: string; title?: string };
    return new ApiError(response.status, problem.detail ?? problem.title ?? response.statusText);
  } catch {
    return new ApiError(response.status, response.statusText);
  }
}
