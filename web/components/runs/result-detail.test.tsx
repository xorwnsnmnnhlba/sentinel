import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import type { RunResult } from "@/lib/api/types";

import { ResultDetail } from "./result-detail";

const responded: RunResult = {
  id: 1,
  testCase: { id: 10, name: "주문 생성" },
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
  responseSchema: { fields: { $: ["object"], "$.orderId": ["number"] }, complete: true },
  drift: { status: "COMPARED", observations: 3, changes: [] },
  change: null,
  retries: 0,
  supporting: false,
};

/** 보관기간 경과 후 API가 돌려주는 형태. 헤더·본문만 null */
const purged: RunResult = {
  ...responded,
  request: { ...responded.request, headers: null, body: null },
  response: { ...responded.response!, headers: null, body: null },
};

describe("ResultDetail", () => {
  it("shows the response structure in its own tab", async () => {
    const user = userEvent.setup();
    render(
      <ResultDetail result={{ ...responded, responseSchema: { ...responded.responseSchema!, complete: false } }} />,
    );

    await user.click(screen.getByRole("tab", { name: "구조" }));

    expect(screen.getByText("이전 실행 3회와 구조가 같습니다")).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "응답 구조" });
    expect(within(table).getByRole("row", { name: "$.orderId number" })).toBeInTheDocument();
    expect(screen.getByText(/일부만 기록했습니다/)).toBeInTheDocument();
  });

  it("offers no structure tab when the response was not JSON", () => {
    render(<ResultDetail result={{ ...responded, responseSchema: null }} />);

    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual(["응답", "요청"]);
  });

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
    // 구조 지문은 원문과 달리 보관기간이 지나도 표시
    expect(screen.getByRole("heading", { name: "응답 구조" })).toBeInTheDocument();
    expect(screen.getByRole("row", { name: "$.orderId number" })).toBeInTheDocument();
  });

  it("says when the response came only after sending again", () => {
    render(<ResultDetail result={{ ...responded, retries: 1 }} />);

    expect(screen.getByText(/처음에 응답을 받지 못해 1회 다시 보낸 뒤 받은 응답입니다/)).toBeInTheDocument();
  });

  it("says when sending again still got no response", () => {
    render(
      <ResultDetail
        result={{
          ...responded,
          verdict: "ERROR",
          outcome: "ERROR",
          assertionResults: [],
          errorReason: "TIMEOUT",
          errorMessage: "TIMEOUT: GET https://gw.dev.internal/orders",
          response: null,
          retries: 2,
        }}
      />,
    );

    expect(screen.getByText("2회 다시 보냈지만 응답을 받지 못했습니다")).toBeInTheDocument();
    expect(screen.queryByText(/다시 보낸 뒤 받은 응답/)).not.toBeInTheDocument();
  });

  it("mentions nothing about sending again when the first attempt responded", () => {
    render(<ResultDetail result={responded} />);

    expect(screen.queryByText(/다시 보/)).not.toBeInTheDocument();
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
      responseSchema: null,
      drift: null,
    };
    render(<ResultDetail result={unsent} rawPurged />);

    expect(screen.getByText("GET (전송 전 실패)")).toBeInTheDocument();
    expect(screen.getByText("UNRESOLVED_VARIABLE")).toBeInTheDocument();
    // 원문이 원래 없던 결과에는 삭제 안내를 붙이지 않음
    expect(screen.queryByText(/헤더와 본문을 삭제했습니다/)).not.toBeInTheDocument();
  });

  it("lists extracted values apart from the rules and hides secret ones", () => {
    render(
      <ResultDetail
        result={{
          ...responded,
          assertionResults: [
            { description: "status in [200]", passed: true, message: "200" },
            { description: "extract {{token}} from $.data.token", passed: true, message: "extracted (hidden)" },
            { description: "extract {{orderUrl}} from header Location", passed: true, message: 'was "/orders/7"' },
            { description: "extract {{id}} from $.id", passed: false, message: "path not found" },
          ],
        }}
      />,
    );

    expect(within(screen.getByRole("table", { name: "판정 규칙" })).getAllByRole("row")).toHaveLength(2);
    const rows = within(screen.getByRole("table", { name: "값 추출" }))
      .getAllByRole("row")
      .slice(1);
    expect(rows.map((row) => row.textContent)).toEqual([
      "통과{{token}}$.data.token가림",
      "통과{{orderUrl}}헤더 Location/orders/7",
      "실패{{id}}$.idpath not found",
    ]);
  });

  it("shows no extraction table for cases without extractors", () => {
    render(<ResultDetail result={responded} />);

    expect(screen.queryByRole("table", { name: "값 추출" })).not.toBeInTheDocument();
  });

  it("explains how to fill a value that an earlier case extracts", () => {
    const missing: RunResult = {
      ...responded,
      outcome: "ERROR",
      verdict: "ERROR",
      assertionResults: [],
      errorReason: "UNRESOLVED_VARIABLE",
      errorMessage: "Unresolved variables: token. Extracted by a case in this suite: token (set only when ...)",
      request: { method: "GET", url: null, headers: null, body: null },
      response: null,
      responseSchema: null,
      drift: null,
    };

    const { unmount } = render(<ResultDetail result={missing} singleCase />);
    expect(screen.getByText(/"여기까지 실행"으로 앞 케이스와 함께 실행해 주세요/)).toBeInTheDocument();
    unmount();

    render(<ResultDetail result={missing} />);
    expect(screen.getByText(/실행 순서와 앞 케이스의 값 추출 결과를 확인해 주세요/)).toBeInTheDocument();
  });

  it("adds no hint for other unresolved variables", () => {
    render(
      <ResultDetail
        result={{
          ...responded,
          errorReason: "UNRESOLVED_VARIABLE",
          errorMessage: "Unresolved variables: userId",
        }}
        singleCase
      />,
    );

    expect(screen.queryByText(/여기까지 실행/)).not.toBeInTheDocument();
  });

  it("adds no hint to other errors that merely quote the same words", () => {
    render(
      <ResultDetail
        result={{
          ...responded,
          errorReason: "INVALID_REQUEST",
          errorMessage: "invalid path: /Extracted by a case in this suite",
        }}
        singleCase
      />,
    );

    expect(screen.queryByText(/여기까지 실행/)).not.toBeInTheDocument();
  });
});
