import "server-only";

import { notFound } from "next/navigation";

/**
 * Spring API 서버 전용 클라이언트. 브라우저 번들 포함 시 빌드 실패(server-only).
 * Spring 주소는 서버 전용 환경변수. NEXT_PUBLIC_ 접두어 사용 시 브라우저에 내부 주소 노출
 */
export const apiBaseUrl = process.env.INTERNAL_API_URL ?? "http://localhost:8080";

/**
 * Spring ProblemDetail 응답 오류. throw 대상이므로 Error 객체에 status·detail을 더한 형태 (클래스 미사용).
 * 판별은 instanceof 대신 isApiError 사용
 */
export type ApiError = Error & { name: "ApiError"; status: number; detail: string };

export function apiError(status: number, detail: string): ApiError {
  return Object.assign(new Error(`API ${status}: ${detail}`), { name: "ApiError" as const, status, detail });
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof Error && error.name === "ApiError" && typeof (error as Partial<ApiError>).status === "number";
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
export async function apiSend<T>(method: "POST" | "PATCH" | "DELETE", path: string, body?: unknown): Promise<T | null> {
  const response = await apiFetch(path, {
    method,
    headers: body === undefined ? { Accept: "application/json" } : { Accept: "application/json", "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    throw await toApiError(response);
  }
  return response.status === 204 ? null : ((await response.json()) as T);
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const problem = (await response.json()) as { detail?: string; title?: string };
    return apiError(response.status, problem.detail ?? problem.title ?? response.statusText);
  } catch {
    return apiError(response.status, response.statusText);
  }
}
