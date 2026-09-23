import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { importCases, previewCaseImport } from "@/lib/actions/case-import";
import type { CaseImportCandidate, CaseImportPreview } from "@/lib/api/types";

import { CaseImportDialog } from "./case-import-dialog";

// 서버 전용 모듈(Server Action)은 테스트용 함수로 대체
vi.mock("@/lib/actions/case-import", () => ({ previewCaseImport: vi.fn(), importCases: vi.fn(async () => ({})) }));

function candidate(source: string, name: string, extra: Partial<CaseImportCandidate> = {}): CaseImportCandidate {
  const [method, path] = source.split(" ");
  return {
    source,
    name,
    request: { method: method as "GET", path, headers: {}, body: null },
    assertions: [{ type: "statusCode", anyOf: ["200"] }],
    warnings: [],
    conflict: false,
    ...extra,
  };
}

const preview: CaseImportPreview = {
  title: "Orders",
  version: "2.0",
  servers: ["https://orders.example.com/api"],
  cases: [
    candidate("GET /orders", "List orders", { conflict: true }),
    candidate("POST /orders", "Create order", { warnings: ["request body has no JSON example; body left empty"] }),
    candidate("GET /orders/{orderId}", "Get order"),
  ],
  warnings: ["TRACE /orders: TRACE is not supported and was skipped"],
};

afterEach(() => {
  vi.mocked(previewCaseImport).mockReset();
  vi.mocked(importCases).mockReset();
  vi.mocked(importCases).mockResolvedValue({});
});

async function openWithPreview() {
  const user = userEvent.setup();
  vi.mocked(previewCaseImport).mockResolvedValue({ preview });
  render(<CaseImportDialog suiteId={3} existingNames={["List orders"]} />);
  await user.click(screen.getByRole("button", { name: "문서 가져오기" }));
  await user.click(screen.getByLabelText("문서"));
  await user.paste("openapi: 3.0.3");
  await user.click(screen.getByRole("button", { name: "미리보기" }));
  await screen.findByText("후보 3건");
  return user;
}

describe("CaseImportDialog", () => {
  it("previews the pasted document", async () => {
    await openWithPreview();

    expect(previewCaseImport).toHaveBeenCalledWith(3, { content: "openapi: 3.0.3", format: "OPENAPI_3" });
    expect(screen.getByText("Orders · 2.0")).toBeInTheDocument();
    expect(screen.getByText("https://orders.example.com/api")).toBeInTheDocument();
    expect(screen.getByText("TRACE /orders: TRACE is not supported and was skipped")).toBeInTheDocument();
    expect(screen.getByText("request body has no JSON example; body left empty")).toBeInTheDocument();
    // 스위트에 이미 있는 이름은 선택 해제 상태로 시작
    expect(screen.getByRole("checkbox", { name: "GET /orders 가져오기" })).not.toBeChecked();
    expect(screen.getByRole("checkbox", { name: "POST /orders 가져오기" })).toBeChecked();
    expect(screen.getByRole("button", { name: "2건 가져오기" })).toBeEnabled();
  });

  it("previews a Postman collection when that format is chosen", async () => {
    const user = userEvent.setup();
    vi.mocked(previewCaseImport).mockResolvedValue({ preview });
    render(<CaseImportDialog suiteId={3} existingNames={[]} />);
    await user.click(screen.getByRole("button", { name: "문서 가져오기" }));

    await user.selectOptions(screen.getByLabelText("형식"), "POSTMAN");
    expect(screen.getByLabelText("문서")).toHaveAttribute("placeholder", expect.stringContaining('"item"'));
    await user.click(screen.getByLabelText("문서"));
    await user.paste('{"info": {}}');
    await user.click(screen.getByRole("button", { name: "미리보기" }));

    await screen.findByText("후보 3건");
    expect(previewCaseImport).toHaveBeenCalledWith(3, { content: '{"info": {}}', format: "POSTMAN" });
  });

  it("reads the document from a file", async () => {
    const user = userEvent.setup();
    render(<CaseImportDialog suiteId={3} existingNames={[]} />);
    await user.click(screen.getByRole("button", { name: "문서 가져오기" }));

    await user.upload(screen.getByLabelText("파일"), new File(["openapi: 3.1.0\n"], "orders.yaml"));

    await waitFor(() => expect(screen.getByLabelText("문서")).toHaveValue("openapi: 3.1.0\n"));
  });

  it("shows why the document could not be read", async () => {
    const user = userEvent.setup();
    vi.mocked(previewCaseImport).mockResolvedValue({ error: "문서를 읽지 못했습니다: document defines no paths" });
    render(<CaseImportDialog suiteId={3} existingNames={[]} />);
    await user.click(screen.getByRole("button", { name: "문서 가져오기" }));
    await user.click(screen.getByLabelText("문서"));
    await user.paste("openapi: 3.0.3");

    await user.click(screen.getByRole("button", { name: "미리보기" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("document defines no paths");
    // 입력한 문서는 유지
    expect(screen.getByLabelText("문서")).toHaveValue("openapi: 3.0.3");
  });

  it("explains a request the server refused to receive", async () => {
    const user = userEvent.setup();
    vi.mocked(previewCaseImport).mockRejectedValue(new Error("Body exceeded 6 MB limit."));
    render(<CaseImportDialog suiteId={3} existingNames={[]} />);
    await user.click(screen.getByRole("button", { name: "문서 가져오기" }));
    await user.click(screen.getByLabelText("문서"));
    await user.paste("openapi: 3.0.3");

    await user.click(screen.getByRole("button", { name: "미리보기" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("요청을 보내지 못했습니다");
  });

  it("blocks names that clash and imports the selection with edited names", async () => {
    const user = await openWithPreview();

    // 기존 이름과 겹치는 후보를 선택하면 막힘
    await user.click(screen.getByRole("checkbox", { name: "GET /orders 가져오기" }));
    expect(screen.getByText("스위트에 이미 있는 이름입니다")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "3건 가져오기" })).toBeDisabled();

    // 이름을 바꾸면 해제, 목록 안 중복도 막힘
    const name = screen.getByRole("textbox", { name: "GET /orders 이름" });
    await user.clear(name);
    await user.type(name, "Get order");
    expect(screen.getAllByText("가져올 목록 안에서 이름이 겹칩니다")).toHaveLength(2);
    await user.clear(name);
    await user.type(name, "List all orders");
    expect(screen.getByRole("button", { name: "3건 가져오기" })).toBeEnabled();

    // 한 건은 제외
    await user.click(screen.getByRole("checkbox", { name: "POST /orders 가져오기" }));
    await user.click(screen.getByRole("button", { name: "2건 가져오기" }));

    await waitFor(() => expect(importCases).toHaveBeenCalledOnce());
    expect(vi.mocked(importCases).mock.calls[0]).toEqual([
      3,
      [
        { name: "List all orders", request: preview.cases[0].request, assertions: preview.cases[0].assertions },
        { name: "Get order", request: preview.cases[2].request, assertions: preview.cases[2].assertions },
      ],
    ]);
    // 성공하면 닫힘
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("keeps the selection when the import fails", async () => {
    const user = await openWithPreview();
    vi.mocked(importCases).mockResolvedValue({ error: "이미 사용 중인 이름이 있습니다: test case name 'Get order'" });

    await user.click(screen.getByRole("button", { name: "2건 가져오기" }));

    const dialog = screen.getByRole("dialog");
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("test case name 'Get order'");
    expect(within(dialog).getByRole("checkbox", { name: "POST /orders 가져오기" })).toBeChecked();
  });

  it("goes back to the document and selects all at once", async () => {
    const user = await openWithPreview();

    await user.click(screen.getByRole("checkbox", { name: "전체 선택" }));
    expect(screen.getByRole("button", { name: "3건 가져오기" })).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: "전체 선택" }));
    expect(screen.getByRole("button", { name: "0건 가져오기" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "문서 다시 선택" }));
    expect(screen.getByLabelText("문서")).toHaveValue("openapi: 3.0.3");
  });
});
