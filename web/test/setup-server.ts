import { afterAll, afterEach, beforeAll, vi } from "vitest";

import { server } from "./msw";

// server-only는 React Server 환경 밖에서 import 시 예외. 테스트에서는 빈 모듈로 대체
vi.mock("server-only", () => ({}));

// 처리하지 않은 요청은 실패로 처리하여 의도하지 않은 Spring 호출 방지
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
