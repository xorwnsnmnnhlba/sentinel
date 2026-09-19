import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { ActionState } from "@/lib/actions/catalog";
import type { EnvironmentItem } from "@/lib/api/types";

import { RunSuiteForm } from "./run-suite-form";

type Action = (state: ActionState, form: FormData) => Promise<ActionState>;

const dev: EnvironmentItem = { id: 1, projectId: 1, name: "dev", baseUrl: "https://gw.dev", variables: {} };
const staging: EnvironmentItem = { id: 2, projectId: 1, name: "staging", baseUrl: "https://gw.stg", variables: {} };

describe("RunSuiteForm", () => {
  it("asks to register an environment first", () => {
    render(<RunSuiteForm environments={[]} caseCount={3} action={vi.fn<Action>()} />);

    expect(screen.getByText("실행하려면 프로젝트에 환경을 먼저 등록해 주세요")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /지금 실행/ })).not.toBeInTheDocument();
  });

  it("disables running a suite without cases", () => {
    render(<RunSuiteForm environments={[dev]} caseCount={0} action={vi.fn<Action>()} />);

    expect(screen.getByRole("button", { name: /지금 실행/ })).toBeDisabled();
    expect(screen.getByText("실행할 케이스가 없습니다")).toBeInTheDocument();
  });

  it("preselects the only environment and submits it", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => undefined);
    render(<RunSuiteForm environments={[dev]} caseCount={2} action={action} />);

    await user.click(screen.getByRole("button", { name: /지금 실행/ }));

    await waitFor(() => expect(action).toHaveBeenCalledOnce());
    expect(action.mock.calls[0][1].get("environmentId")).toBe("1");
  });

  it("submits the chosen environment among several", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => undefined);
    render(<RunSuiteForm environments={[dev, staging]} caseCount={2} action={action} />);

    // 키보드로 목록 열기. jsdom은 레이아웃이 없어 Radix 목록이 접근성 트리에서 숨김으로 판정되므로 목록 안의 텍스트로 선택
    screen.getByRole("combobox", { name: "실행 환경" }).focus();
    await user.keyboard("{Enter}");
    await user.click(within(await screen.findByRole("listbox", { hidden: true })).getByText("staging"));
    await user.click(screen.getByRole("button", { name: /지금 실행/ }));

    await waitFor(() => expect(action).toHaveBeenCalledOnce());
    expect(action.mock.calls[0][1].get("environmentId")).toBe("2");
  });

  it("shows the action error", async () => {
    const user = userEvent.setup();
    render(
      <RunSuiteForm environments={[dev]} caseCount={2} action={async () => ({ error: "대상을 찾을 수 없습니다" })} />,
    );

    await user.click(screen.getByRole("button", { name: /지금 실행/ }));

    expect(await screen.findByRole("alert")).toHaveTextContent("대상을 찾을 수 없습니다");
  });
});
