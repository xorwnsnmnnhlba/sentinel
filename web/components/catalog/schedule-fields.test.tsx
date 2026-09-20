import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { Button } from "@/components/ui/button";
import type { ActionState } from "@/lib/actions/catalog";
import type { EnvironmentItem, ScheduleDetail } from "@/lib/api/types";

import { FormDialog } from "./form-dialog";
import { ScheduleFields } from "./schedule-fields";

type Action = (state: ActionState, form: FormData) => Promise<ActionState>;

const dev: EnvironmentItem = { id: 1, projectId: 1, name: "dev", baseUrl: "https://gw.dev", variables: {} };
const staging: EnvironmentItem = { id: 2, projectId: 1, name: "staging", baseUrl: "https://gw.stg", variables: {} };

const schedule: ScheduleDetail = {
  id: 7,
  suiteId: 1,
  environmentId: 2,
  cron: "30 17 * * 1-5",
  zone: "Asia/Seoul",
  enabled: true,
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
    expect(Object.fromEntries(action.mock.calls[0][1])).toEqual({ environmentId: "2", cron: "0 */2 * * *" });
  });

  it("edits cron only and keeps the saved value", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => ({}));
    renderDialog(action, <ScheduleFields schedule={schedule} />);

    await user.click(screen.getByRole("button", { name: "열기" }));
    expect(screen.getByLabelText("cron")).toHaveValue("30 17 * * 1-5");
    expect(screen.queryByLabelText("실행 환경")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "저장" }));

    await waitForCall(action);
    expect(Object.fromEntries(action.mock.calls[0][1])).toEqual({ cron: "30 17 * * 1-5" });
  });
});

async function waitForCall(action: ReturnType<typeof vi.fn>) {
  await vi.waitFor(() => expect(action).toHaveBeenCalledOnce());
}
