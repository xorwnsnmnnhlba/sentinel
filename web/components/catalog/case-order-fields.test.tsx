import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { CaseOrderFields, type OrderedCase } from "./case-order-fields";

const cases: OrderedCase[] = [
  { id: 11, name: "로그인", method: "POST", path: "/login" },
  { id: 12, name: "주문 등록", method: "POST", path: "/orders" },
  { id: 13, name: "주문 조회", method: "GET", path: "/orders/{{orderId}}" },
];

function renderInForm() {
  const { container } = render(
    <form>
      <CaseOrderFields cases={cases} />
    </form>,
  );
  const form = container.querySelector("form")!;
  return {
    submitted: () => new FormData(form).getAll("caseId"),
    names: () =>
      within(screen.getByRole("list", { name: "실행 순서" }))
        .getAllByRole("listitem")
        .map((item) => item.querySelector("p")!.textContent),
  };
}

describe("CaseOrderFields", () => {
  it("moves cases one step at a time and submits their ids in the new order", async () => {
    const user = userEvent.setup();
    const { submitted, names } = renderInForm();

    await user.click(screen.getByRole("button", { name: "주문 조회 위로 이동" }));
    await user.click(screen.getByRole("button", { name: "로그인 아래로 이동" }));

    expect(names()).toEqual(["주문 조회", "로그인", "주문 등록"]);
    expect(submitted()).toEqual(["13", "11", "12"]);
  });

  it("disables moves past either end", () => {
    renderInForm();

    expect(screen.getByRole("button", { name: "로그인 위로 이동" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "주문 조회 아래로 이동" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "주문 등록 위로 이동" })).toBeEnabled();
  });

  it("keeps focus on the moved case and announces its new place", async () => {
    const user = userEvent.setup();
    renderInForm();

    await user.click(screen.getByRole("button", { name: "주문 등록 위로 이동" }));

    // 맨 위에 닿아 위로 버튼이 비활성화되었으므로 같은 케이스의 아래로 버튼
    expect(screen.getByRole("button", { name: "주문 등록 아래로 이동" })).toHaveFocus();
    expect(screen.getByText("주문 등록: 3개 중 1번째")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "주문 등록 아래로 이동" }));

    expect(screen.getByRole("button", { name: "주문 등록 아래로 이동" })).toHaveFocus();
    expect(screen.getByText("주문 등록: 3개 중 2번째")).toBeInTheDocument();
  });
});
