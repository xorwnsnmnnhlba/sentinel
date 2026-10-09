import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { CaseActionState } from "@/lib/actions/catalog";
import type { EnvironmentItem } from "@/lib/api/types";
import { type CaseDraft, emptyDraft } from "@/lib/case-draft";

import { CaseEditor } from "./case-editor";

type Action = (draft: CaseDraft, runIn?: number, through?: boolean) => Promise<CaseActionState>;

function renderEditor(action: Action, initial: CaseDraft = emptyDraft(), environments: EnvironmentItem[] = []) {
  render(
    <CaseEditor
      initial={initial}
      action={action}
      cancelHref="/suites/1"
      submitLabel="등록"
      environments={environments}
    />,
  );
}

const valid: CaseDraft = { ...emptyDraft(), name: "health", path: "/health" };

function environment(id: number, name: string): EnvironmentItem {
  return { id, project: { id: 1, name: "project-1" }, name, baseUrl: "http://gw", variables: {}, secretNames: [] };
}

describe("CaseEditor", () => {
  it("builds a request with headers, body and rules", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => undefined);
    renderEditor(action);

    await user.type(screen.getByLabelText("이름"), "결제 요청");
    await user.selectOptions(screen.getByLabelText("메서드"), "POST");
    await user.clear(screen.getByLabelText("경로"));
    await user.type(screen.getByLabelText("경로"), "/billing/v1/charge");
    await user.click(screen.getByRole("button", { name: "헤더 추가" }));
    await user.type(screen.getByLabelText("헤더 1 이름"), "Content-Type");
    await user.type(screen.getByLabelText("헤더 1 값"), "application/json");
    // user-event에서 { 는 특수 문자이므로 {{ 로 입력
    await user.type(screen.getByLabelText(/본문/), '{{"amount":1000}');
    await user.click(screen.getByRole("button", { name: "JSON 정렬" }));

    await user.click(screen.getByRole("button", { name: "규칙 추가" }));
    await user.selectOptions(screen.getByLabelText("추가할 판정 규칙"), "jsonPath");
    await user.click(screen.getByRole("button", { name: "규칙 추가" }));
    await user.type(screen.getByLabelText("판정 규칙 2 JsonPath"), "amount");
    await user.selectOptions(screen.getByLabelText("판정 규칙 2 비교"), "EQUALS");
    await user.type(screen.getByLabelText("판정 규칙 2 기대값"), "1000");
    await user.click(screen.getByRole("button", { name: "등록" }));

    await waitFor(() => expect(action).toHaveBeenCalledOnce());
    expect(action.mock.calls[0][0]).toEqual({
      name: "결제 요청",
      method: "POST",
      path: "/billing/v1/charge",
      headers: [{ name: "Content-Type", value: "application/json" }],
      body: '{\n  "amount": 1000\n}',
      assertions: [
        { type: "statusCode", anyOf: "2xx" },
        { type: "jsonPath", path: "$.amount", operator: "EQUALS", expected: "1000" },
      ],
      extractors: [],
    });
  });

  it("adds extractors that later cases can use as variables", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => undefined);
    renderEditor(action, valid);

    await user.click(screen.getByRole("button", { name: "추출 추가" }));
    await user.type(screen.getByLabelText("값 추출 1 변수 이름"), "token");
    await user.type(screen.getByLabelText("값 추출 1 JsonPath"), "data.token");
    await user.click(screen.getByRole("switch", { name: "값 추출 1 값 가리기" }));
    await user.click(screen.getByRole("button", { name: "추출 추가" }));
    await user.type(screen.getByLabelText("값 추출 2 변수 이름"), "orderUrl");
    // 입력하지 않은 예시 값은 위치를 바꾸면 새 위치의 예시로 교체
    await user.selectOptions(screen.getByLabelText("값 추출 2 위치"), "HEADER");
    expect(screen.getByLabelText("값 추출 2 헤더 이름")).toHaveValue("Location");
    // 제목에 뒤 케이스에서 쓸 변수 표기
    expect(screen.getByRole("heading", { name: "1. {{token}}" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "등록" }));

    await waitFor(() => expect(action).toHaveBeenCalledOnce());
    expect(action.mock.calls[0][0].extractors).toEqual([
      { name: "token", from: "BODY", expression: "$.data.token", secret: true },
      { name: "orderUrl", from: "HEADER", expression: "Location", secret: false },
    ]);
  });

  it("keeps a typed expression when the source changes and removes extractors", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => undefined);
    renderEditor(action, {
      ...valid,
      extractors: [
        { name: "id", from: "BODY", expression: "$.id", secret: false },
        { name: "trace", from: "HEADER", expression: "X-Trace-Id", secret: false },
      ],
    });

    await user.selectOptions(screen.getByLabelText("값 추출 1 위치"), "HEADER");
    expect(screen.getByLabelText("값 추출 1 헤더 이름")).toHaveValue("$.id");
    await user.click(screen.getByRole("button", { name: "값 추출 1 삭제" }));

    expect(screen.getByLabelText("값 추출 1 변수 이름")).toHaveValue("trace");
    expect(screen.queryByLabelText("값 추출 2 변수 이름")).not.toBeInTheDocument();
  });

  it("shows extractor errors without calling the action", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => undefined);
    renderEditor(action, valid);

    await user.click(screen.getByRole("button", { name: "추출 추가" }));
    await user.click(screen.getByRole("button", { name: "등록" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("값 추출 1: 변수 이름은");
    expect(action).not.toHaveBeenCalled();
  });

  it("stops adding extractors at the limit", () => {
    const extractors = Array.from({ length: 20 }, (_, i) => ({
      name: `v${i}`,
      from: "BODY" as const,
      expression: "$.v",
      secret: false,
    }));
    renderEditor(vi.fn<Action>(), { ...valid, extractors });

    expect(screen.getByRole("button", { name: "추출 추가" })).toBeDisabled();
  });

  it("shows validation errors without calling the action", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => undefined);
    renderEditor(action);

    await user.clear(screen.getByLabelText("경로"));
    await user.type(screen.getByLabelText("경로"), "health");
    await user.selectOptions(screen.getByLabelText("추가할 판정 규칙"), "bodyContains");
    await user.click(screen.getByRole("button", { name: "규칙 추가" }));
    await user.click(screen.getByRole("button", { name: "등록" }));

    const alert = await screen.findByRole("alert");
    expect(
      within(alert)
        .getAllByRole("listitem")
        .map((item) => item.textContent),
    ).toEqual([
      "이름을 입력해 주세요",
      "경로는 / 또는 {{변수}}로 시작해야 합니다 (기본 URL 뒤에 붙는 상대 경로)",
      "판정 규칙 1 (본문 포함): 포함할 문자열을 입력해 주세요",
    ]);
    expect(action).not.toHaveBeenCalled();
  });

  it("shows server errors and keeps the form", async () => {
    const user = userEvent.setup();
    renderEditor(async () => ({ errors: ["이미 사용 중인 이름입니다"] }), { ...emptyDraft(), name: "health" });

    await user.click(screen.getByRole("button", { name: "등록" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("이미 사용 중인 이름입니다");
    expect(screen.getByLabelText("이름")).toHaveValue("health");
  });

  it("disables the value when the operator needs none", async () => {
    const user = userEvent.setup();
    renderEditor(vi.fn<Action>());

    await user.selectOptions(screen.getByLabelText("추가할 판정 규칙"), "header");
    await user.click(screen.getByRole("button", { name: "규칙 추가" }));
    expect(screen.getByLabelText("판정 규칙 1 값")).toBeEnabled();

    await user.selectOptions(screen.getByLabelText("판정 규칙 1 비교"), "EXISTS");
    expect(screen.getByLabelText("판정 규칙 1 값")).toBeDisabled();
    // 응답 헤더는 숫자 비교 선택지 없음
    expect(within(screen.getByLabelText("판정 규칙 1 비교")).queryByRole("option", { name: /초과/ })).toBeNull();
  });

  it("removes a rule and renumbers the rest", async () => {
    const user = userEvent.setup();
    renderEditor(vi.fn<Action>(), {
      ...emptyDraft(),
      assertions: [
        { type: "statusCode", anyOf: "200" },
        { type: "bodyContains", text: "OK" },
      ],
    });

    await user.click(screen.getByRole("button", { name: "판정 규칙 1 삭제" }));

    expect(screen.queryByLabelText("판정 규칙 1 상태 코드")).not.toBeInTheDocument();
    expect(screen.getByLabelText("판정 규칙 1 포함할 문자열")).toHaveValue("OK");
  });

  it("links back without saving", () => {
    renderEditor(vi.fn<Action>());

    expect(screen.getByRole("link", { name: "취소" })).toHaveAttribute("href", "/suites/1");
  });

  it("saves only, without running, from the main button", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => undefined);
    renderEditor(action, valid, [environment(3, "dev")]);

    await user.click(screen.getByRole("button", { name: "등록" }));

    await waitFor(() => expect(action).toHaveBeenCalledOnce());
    expect(action.mock.calls[0][1]).toBeUndefined();
  });

  it("saves and runs in the only environment", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => undefined);
    renderEditor(action, valid, [environment(3, "dev")]);

    // 환경이 하나면 고를 필요 없음
    expect(screen.queryByRole("combobox", { name: "실행 환경" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "등록 후 실행" }));

    await waitFor(() => expect(action).toHaveBeenCalledOnce());
    expect(action.mock.calls[0]).toEqual([valid, 3]);
  });

  it("runs in the chosen environment when there are several", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => undefined);
    renderEditor(action, valid, [environment(3, "dev"), environment(5, "staging")]);

    await user.selectOptions(screen.getByRole("combobox", { name: "실행 환경" }), "staging");
    await user.click(screen.getByRole("button", { name: "등록 후 실행" }));

    await waitFor(() => expect(action).toHaveBeenCalledOnce());
    expect(action.mock.calls[0][1]).toBe(5);
  });

  it("saves and runs from the start of the suite through this case", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => undefined);
    render(
      <CaseEditor
        initial={valid}
        action={action}
        cancelHref="/suites/1"
        submitLabel="저장"
        environments={[environment(3, "dev")]}
        runThrough
      />,
    );

    await user.click(screen.getByRole("button", { name: "저장 후 여기까지 실행" }));

    await waitFor(() => expect(action).toHaveBeenCalledOnce());
    expect(action.mock.calls[0]).toEqual([valid, 3, true]);
  });

  it("offers run through only when asked", () => {
    renderEditor(vi.fn<Action>(), valid, [environment(3, "dev")]);

    expect(screen.queryByRole("button", { name: /여기까지 실행/ })).not.toBeInTheDocument();
  });

  it("offers no run button without environments", () => {
    renderEditor(vi.fn<Action>(), valid, []);

    expect(screen.queryByRole("button", { name: /후 실행/ })).not.toBeInTheDocument();
  });

  it("shows why it could not run and keeps the form", async () => {
    const user = userEvent.setup();
    renderEditor(async () => ({ errors: ["저장했지만 실행하지 못했습니다. 대상을 찾을 수 없습니다"] }), valid, [
      environment(3, "dev"),
    ]);

    await user.click(screen.getByRole("button", { name: "등록 후 실행" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("저장했지만 실행하지 못했습니다");
    expect(screen.getByLabelText("이름")).toHaveValue("health");
  });
});
