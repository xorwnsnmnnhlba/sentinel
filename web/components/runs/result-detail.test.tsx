import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { RunResult } from "@/lib/api/types";

import { ResultDetail } from "./result-detail";

const responded: RunResult = {
  id: 1,
  testCaseId: 10,
  testCaseName: "주문 생성",
  verdict: "PASSED",
  outcome: "RESPONDED",
  assertionResults: [{ description: "status in [200]", passed: true, message: "200" }],
  errorReason: null,
  errorMessage: null,
  elapsedMs: 12,
  request: {
    method: "POST",
    url: "https://gw.dev.internal/orders",
    headers: { "X-Trace": ["trace-1"] },
    body: '{"item":1}',
  },
  response: {
    statusCode: 200,
    headers: { "Content-Type": ["application/json"] },
    body: '{"orderId":7}',
    bodyTruncated: false,
  },
};

/** 보관기간 경과 후 API가 돌려주는 형태. 헤더·본문만 null */
const purged: RunResult = {
  ...responded,
  request: { ...responded.request, headers: null, body: null },
  response: { ...responded.response!, headers: null, body: null },
};

describe("ResultDetail", () => {
  it("shows the raw response while it is kept", () => {
    render(<ResultDetail result={responded} />);

    expect(screen.getByText("POST https://gw.dev.internal/orders")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "응답" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText("Content-Type")).toBeInTheDocument();
    expect(screen.getByText(/"orderId": 7/)).toBeInTheDocument();
    expect(screen.queryByText(/헤더와 본문을 삭제했습니다/)).not.toBeInTheDocument();
  });

  it("explains that the raw payload was purged instead of showing it as empty", () => {
    render(<ResultDetail result={purged} rawPurged />);

    expect(screen.getByText("보관기간이 지나 요청·응답 헤더와 본문을 삭제했습니다")).toBeInTheDocument();
    // 비어 있던 것처럼 보이지 않도록 원문 탭 자체를 표시하지 않음
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
    expect(screen.queryByText("헤더 없음")).not.toBeInTheDocument();
    expect(screen.queryByText("본문 없음")).not.toBeInTheDocument();
    // 요청 주소와 판정 규칙 결과는 유지
    expect(screen.getByText("POST https://gw.dev.internal/orders")).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "status in [200]" })).toBeInTheDocument();
  });

  it("shows only the error for a request that was never sent", () => {
    const unsent: RunResult = {
      ...responded,
      verdict: "ERROR",
      outcome: "ERROR",
      assertionResults: [],
      errorReason: "UNRESOLVED_VARIABLE",
      errorMessage: "missing variable: token",
      request: { method: "GET", url: null, headers: null, body: null },
      response: null,
    };
    render(<ResultDetail result={unsent} rawPurged />);

    expect(screen.getByText("GET (전송 전 실패)")).toBeInTheDocument();
    expect(screen.getByText("UNRESOLVED_VARIABLE")).toBeInTheDocument();
    // 원문이 원래 없던 결과에는 삭제 안내를 붙이지 않음
    expect(screen.queryByText(/헤더와 본문을 삭제했습니다/)).not.toBeInTheDocument();
  });
});
