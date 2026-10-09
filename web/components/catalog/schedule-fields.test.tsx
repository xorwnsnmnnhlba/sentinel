import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { Button } from "@/components/ui/button";
import type { ActionState } from "@/lib/actions/catalog";
import type { EnvironmentItem, ScheduleDetail } from "@/lib/api/types";

import { FormDialog } from "./form-dialog";
import { ScheduleFields } from "./schedule-fields";

type Action = (state: ActionState, form: FormData) => Promise<ActionState>;

const dev: EnvironmentItem = {
  id: 1,
  project: { id: 1, name: "project-1" },
  name: "dev",
  baseUrl: "https://gw.dev",
  variables: {},
  secretNames: [],
};
const staging: EnvironmentItem = {
  id: 2,
  project: { id: 1, name: "project-1" },
  name: "staging",
  baseUrl: "https://gw.stg",
  variables: {},
  secretNames: [],
};

const schedule: ScheduleDetail = {
  id: 7,
  project: { id: 1, name: "project-1" },
  suite: { id: 1, name: "smoke" },
  environment: { id: 2, name: "staging" },
  cron: "30 17 * * 1-5",
  zone: "Asia/Seoul",
  enabled: true,
  skipHolidays: true,
  nextRunAt: "2026-09-21T08:30:00Z",
  lastTriggeredAt: null,
};

function renderDialog(action: Action, fields: React.ReactNode) {
  render(
    <FormDialog trigger={<Button>열기</Button>} title="스케줄" submitLabel="저장" action={action}>
      {fields}
    </FormDialog>,
  );
}

describe("ScheduleFields", () => {
  it("submits the chosen environment and cron when adding", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => ({}));
    renderDialog(action, <ScheduleFields environments={[dev, staging]} />);

    await user.click(screen.getByRole("button", { name: "열기" }));
    await user.selectOptions(screen.getByLabelText("실행 환경"), "2");
    await user.clear(screen.getByLabelText("cron"));
    await user.type(screen.getByLabelText("cron"), "0 */2 * * *");
    await user.click(screen.getByRole("button", { name: "저장" }));

    await waitForCall(action);
    // 휴일 건너뛰기는 기본 꺼짐 (체크 해제된 상자는 폼에 없음)
    expect(Object.fromEntries(action.mock.calls[0][1])).toEqual({ environmentId: "2", cron: "0 */2 * * *" });
  });

  it("submits holiday skipping when checked", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => ({}));
    renderDialog(action, <ScheduleFields environments={[dev, staging]} />);

    await user.click(screen.getByRole("button", { name: "열기" }));
    const skip = screen.getByRole("checkbox", { name: "휴일 건너뛰기" });
    expect(skip).not.toBeChecked();
    expect(skip).toHaveAccessibleDescription("휴일 화면에 등록한 날에는 실행하지 않음");
    await user.click(skip);
    await user.click(screen.getByRole("button", { name: "저장" }));

    await waitForCall(action);
    expect(Object.fromEntries(action.mock.calls[0][1])).toMatchObject({ skipHolidays: "true" });
  });

  it("keeps the saved cron and environment when editing", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => ({}));
    renderDialog(action, <ScheduleFields environments={[dev, staging]} schedule={schedule} />);

    await user.click(screen.getByRole("button", { name: "열기" }));
    expect(screen.getByLabelText("cron")).toHaveValue("30 17 * * 1-5");
    // 저장된 환경·휴일 건너뛰기가 선택된 상태
    expect(screen.getByLabelText("실행 환경")).toHaveValue("2");
    expect(screen.getByRole("checkbox", { name: "휴일 건너뛰기" })).toBeChecked();

    await user.click(screen.getByRole("button", { name: "저장" }));

    await waitForCall(action);
    expect(Object.fromEntries(action.mock.calls[0][1])).toEqual({
      cron: "30 17 * * 1-5",
      environmentId: "2",
      skipHolidays: "true",
    });
  });

  it("changes the environment of a saved schedule", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => ({}));
    renderDialog(action, <ScheduleFields environments={[dev, staging]} schedule={schedule} />);

    await user.click(screen.getByRole("button", { name: "열기" }));
    await user.selectOptions(screen.getByLabelText("실행 환경"), "1");
    await user.click(screen.getByRole("checkbox", { name: "휴일 건너뛰기" }));
    await user.click(screen.getByRole("button", { name: "저장" }));

    await waitForCall(action);
    expect(Object.fromEntries(action.mock.calls[0][1])).toEqual({ cron: "30 17 * * 1-5", environmentId: "1" });
  });
});

async function waitForCall(action: ReturnType<typeof vi.fn>) {
  await vi.waitFor(() => expect(action).toHaveBeenCalledOnce());
}
