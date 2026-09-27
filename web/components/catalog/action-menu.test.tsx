import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { ProjectFields } from "@/components/catalog/entity-fields";
import type { ActionState } from "@/lib/actions/catalog";
import { openMenu } from "@/test/menu";

import { ActionMenu } from "./action-menu";
import { DeleteDialog } from "./delete-dialog";
import { FormDialog } from "./form-dialog";
import { caseMenu, EDIT_DELETE } from "./menu-items";

type Action = (state: ActionState, form: FormData) => Promise<ActionState>;

describe("ActionMenu", () => {
  it("lists the actions with delete last, after a separator", async () => {
    const user = userEvent.setup();
    render(<ActionMenu label="환경 self 작업" items={EDIT_DELETE} />);

    await openMenu(user, "환경 self 작업");

    const menu = await screen.findByRole("menu");
    expect(
      within(menu)
        .getAllByRole("menuitem")
        .map((item) => item.textContent),
    ).toEqual(["수정", "삭제"]);
    expect(within(menu).getByRole("separator")).toBeInTheDocument();
  });

  it("opens the edit dialog linked by id, outside the closed menu", async () => {
    const user = userEvent.setup();
    const update = vi.fn<Action>(async () => ({}));
    render(
      <ActionMenu label="프로젝트 billing 작업" items={EDIT_DELETE}>
        <FormDialog menuId="edit" title="프로젝트 수정" submitLabel="저장" action={update}>
          <ProjectFields project={{ id: 1, name: "billing", description: null }} />
        </FormDialog>
        <DeleteDialog menuId="delete" title="삭제" description="삭제합니다" action={async () => ({})} />
      </ActionMenu>,
    );

    await openMenu(user, "프로젝트 billing 작업");
    await user.click(await screen.findByRole("menuitem", { name: "수정" }));

    const dialog = await screen.findByRole("dialog", { name: "프로젝트 수정" });
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    // 같은 메뉴의 다른 대화상자는 닫힌 채
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(within(dialog).getByLabelText("이름")).toHaveValue("billing");

    await user.click(within(dialog).getByRole("button", { name: "저장" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(update).toHaveBeenCalledOnce();
    await waitFor(() => expect(screen.getByRole("button", { name: "프로젝트 billing 작업" })).toHaveFocus());
  });

  it("orders the case actions with delete last", async () => {
    const user = userEvent.setup();
    render(
      <ActionMenu label="케이스 조회 작업" items={caseMenu(7, { runnable: true, first: false, duplicate: vi.fn() })} />,
    );

    await openMenu(user, "케이스 조회 작업");

    const menu = await screen.findByRole("menu");
    expect(
      within(menu)
        .getAllByRole("menuitem")
        .map((item) => item.textContent),
    ).toEqual(["실행", "여기까지 실행", "수정", "복제", "삭제"]);
  });

  it("offers no run through for the first case, where it equals running the case alone", async () => {
    const user = userEvent.setup();
    render(
      <ActionMenu label="케이스 조회 작업" items={caseMenu(7, { runnable: true, first: true, duplicate: vi.fn() })} />,
    );

    await openMenu(user, "케이스 조회 작업");

    expect(within(await screen.findByRole("menu")).queryByRole("menuitem", { name: "여기까지 실행" })).toBeNull();
  });

  it("runs an action item right away without a dialog", async () => {
    const user = userEvent.setup();
    const duplicate = vi.fn(async () => undefined);
    render(<ActionMenu label="케이스 조회 작업" items={caseMenu(7, { runnable: false, first: false, duplicate })} />);

    await openMenu(user, "케이스 조회 작업");
    await user.click(await screen.findByRole("menuitem", { name: "복제" }));

    await waitFor(() => expect(duplicate).toHaveBeenCalledOnce());
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("explains why an action failed and returns focus to the menu button", async () => {
    const user = userEvent.setup();
    const duplicate = vi.fn(async () => ({ error: "이미 사용 중인 이름입니다" }));
    render(<ActionMenu label="케이스 조회 작업" items={caseMenu(7, { runnable: false, first: false, duplicate })} />);

    await openMenu(user, "케이스 조회 작업");
    await user.click(await screen.findByRole("menuitem", { name: "복제" }));

    const alert = await screen.findByRole("alertdialog", { name: "작업을 완료하지 못했습니다" });
    expect(alert).toHaveTextContent("이미 사용 중인 이름입니다");
    await user.click(within(alert).getByRole("button", { name: "확인" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByRole("button", { name: "케이스 조회 작업" })).toHaveFocus());
  });

  it("navigates for link items instead of opening a dialog", async () => {
    const user = userEvent.setup();
    render(
      <ActionMenu
        label="케이스 조회 작업"
        items={caseMenu(7, { runnable: false, first: false, duplicate: vi.fn() })}
      />,
    );

    await openMenu(user, "케이스 조회 작업");

    expect(await screen.findByRole("menuitem", { name: "수정" })).toHaveAttribute("href", "/cases/7");
  });
});
