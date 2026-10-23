import "server-only";

import { notFound, redirect } from "next/navigation";

import { sessionToken } from "./session";

/**
 * Spring API 서버 전용 클라이언트. 브라우저 번들 포함 시 빌드 실패(server-only).
 * Spring 주소는 서버 전용 환경변수. NEXT_PUBLIC_ 접두어 사용 시 브라우저에 내부 주소 노출
 */
export const apiBaseUrl = process.env.INTERNAL_API_URL ?? "http://localhost:8080";

/** 끝의 / 제외 (경로를 이어 붙일 때 // 가 되지 않도록. Spring은 // 경로를 항상 인증 요구) */
const apiOrigin = apiBaseUrl.replace(/\/+$/, "");

/**
 * Spring ProblemDetail 응답 오류. throw 대상이므로 Error 객체에 status·detail을 더한 형태 (클래스 미사용).
 * 판별은 instanceof 대신 isApiError 사용
 * code·params는 화면이 한국어 문장을 만들도록 서버가 붙인 오류 종류와 값 (예: 가져올 수 없는 문서). 없으면 detail만
 */
export type ApiError = Error & {
  name: "ApiError";
  status: number;
  detail: string;
  code?: string;
  params?: Record<string, string>;
};

export function apiError(
  status: number,
  detail: string,
  problem: { code?: string; params?: Record<string, string> } = {},
): ApiError {
  return Object.assign(new Error(`API ${status}: ${detail}`), {
    name: "ApiError" as const,
    status,
    detail,
    ...problem,
  });
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof Error && error.name === "ApiError" && typeof (error as Partial<ApiError>).status === "number";
}

/**
 * 최신 실행 결과를 보여주는 도구이므로 캐시 미사용.
 * 세션 쿠키가 있으면 Spring에는 Authorization 헤더로 전달 (Spring은 쿠키를 모름)
 */
export async function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  const url = apiUrl(path);
  const token = await sessionToken();
  const headers = new Headers(init?.headers);
  if (token && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  return fetch(url, { ...init, headers, cache: "no-store" });
}

/**
 * Spring 주소. /api/로 시작하는 경로만 받음. "//host/..."나 "https://..."는 new URL이 기본 주소를 무시하여
 * 세션 토큰을 다른 서버로 보내게 되므로 거부 (지금 호출은 모두 고정된 /api/로 시작하지만 실수로 바뀌어도 막음).
 * "/a"로 시작하는 경로는 항상 기본 주소의 서버를 가리킴
 */
function apiUrl(path: string): string {
  if (!path.startsWith("/api/")) {
    throw new Error("API path must be a /api/ path on the API server");
  }
  // 고정된 "/api/" 뒤에만 이어 붙여 경로가 호스트를 바꿀 수 없음이 코드 모양으로 드러나게 함 (CodeQL js/request-forgery)
  return `${apiOrigin}/api/${path.slice("/api/".length)}`;
}

/** 로그인 화면으로 이동. 세션 만료·미로그인 상태에서 보호된 화면을 열 때 사용 */
export function redirectToLogin(from?: string): never {
  redirect(from && from !== "/" ? `/login?from=${encodeURIComponent(from)}` : "/login");
}

export async function apiGet<T>(path: string): Promise<T> {
  const response = await apiFetch(path, { headers: { Accept: "application/json" } });
  if (response.status === 401) {
    // 세션 만료. 오류 화면 대신 로그인 화면으로
    redirectToLogin();
  }
  if (!response.ok) {
    throw await toApiError(response);
  }
  return (await response.json()) as T;
}

/** 단건 조회. 404는 notFound()로 404 화면 표시 */
export async function apiGetOrNotFound<T>(path: string): Promise<T> {
  try {
    return await apiGet<T>(path);
  } catch (error) {
    if (isApiError(error) && error.status === 404) {
      notFound();
    }
    throw error;
  }
}

/**
 * 등록·수정·삭제·실행 요청. 204(본문 없음)는 null 반환, 2xx 외 응답은 ApiError
 */
export async function apiSend<T>(
  method: "POST" | "PUT" | "PATCH" | "DELETE",
  path: string,
  body?: unknown,
): Promise<T | null> {
  const response = await apiFetch(path, {
    method,
    headers:
      body === undefined
        ? { Accept: "application/json" }
        : { Accept: "application/json", "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    throw await toApiError(response);
  }
  return response.status === 204 ? null : ((await response.json()) as T);
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const problem = (await response.json()) as { detail?: string; title?: string; code?: unknown; params?: unknown };
    return apiError(response.status, problem.detail ?? problem.title ?? response.statusText, {
      ...(typeof problem.code === "string" && { code: problem.code }),
      ...(isStringRecord(problem.params) && { params: problem.params }),
    });
  } catch {
    return apiError(response.status, response.statusText);
  }
}

function isStringRecord(value: unknown): value is Record<string, string> {
  return (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value) &&
    Object.values(value).every((entry) => typeof entry === "string")
  );
}
