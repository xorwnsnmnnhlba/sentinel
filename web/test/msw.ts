import { setupServer } from "msw/node";

/** Spring API 대체 서버. 기본 핸들러 없이 테스트마다 server.use()로 응답 지정 */
export const server = setupServer();

/** lib/api/server.ts 기본 주소(INTERNAL_API_URL 미지정) 기준 Spring URL */
export function springUrl(path: string): string {
  return new URL(path, "http://localhost:8080").toString();
}

/** Spring ProblemDetail 응답 본문 */
export function problem(status: number, detail: string) {
  return Response.json({ type: "about:blank", title: "Error", status, detail }, { status });
}

/** 화면이 한국어 문장을 만들 code·params가 붙은 오류 응답 */
export function codedProblem(status: number, detail: string, code: string, params: Record<string, string> = {}) {
  return Response.json({ type: "about:blank", title: "Error", status, detail, code, params }, { status });
}
