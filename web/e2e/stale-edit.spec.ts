import type { APIRequestContext } from "@playwright/test";

import { chooseAction, expect, test } from "./fixtures";

/**
 * 수정 화면을 연 뒤 다른 곳에서 먼저 저장한 환경은 저장 거부 (열어 둔 옛 내용으로 덮어쓰지 않음)
 * 다른 사람의 저장은 API로 대신함. 비밀번호는 저장소에 있는 E2E 테스트 계정 값
 */
const api = process.env.E2E_API_URL ?? "http://localhost:18080";
const stamp = Date.now();

let projectId = 0;
let environmentId = 0;

async function call(request: APIRequestContext, method: "POST" | "PATCH" | "GET", path: string, data?: object) {
  const session = await request.post(`${api}/api/sessions`, {
    data: { email: "admin@sentinel.test", password: "e2e-admin-password" },
  });
  const headers = { Authorization: `Bearer ${(await session.json()).token}` };
  const response = await request.fetch(`${api}${path}`, { method, headers, data });
  expect(response.ok(), `${method} ${path} ${response.status()}`).toBe(true);
  return response.json();
}

test.beforeAll(async ({ request }) => {
  projectId = (await call(request, "POST", "/api/projects", { name: `stale-${stamp}` })).id;
  environmentId = (
    await call(request, "POST", "/api/environments", {
      projectId,
      name: "dev",
      baseUrl: "https://gw.example",
      variables: { userId: "42" },
    })
  ).id;
});

test("열어 둔 환경 수정 화면은 그사이 다른 곳에서 저장했으면 저장하지 않음", async ({ page, request }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"));
  await page.goto(`/projects/${projectId}`);

  await chooseAction(page, "환경 dev 작업", "수정");
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByLabel("변수 1 값")).toHaveValue("42");

  // 다른 사람이 변수를 추가해 저장
  await call(request, "PATCH", `/api/environments/${environmentId}`, {
    variables: { userId: "42", locale: "ko-KR" },
  });

  await dialog.getByLabel("변수 1 값").fill("7");
  await dialog.getByRole("button", { name: "저장" }).click();
  await expect(
    dialog.getByText("다른 곳에서 먼저 수정했습니다. 최신 내용을 확인한 뒤 다시 시도해 주세요"),
  ).toBeVisible();
  // 다시 눌러도 열어 둔 내용으로는 저장되지 않음
  await dialog.getByRole("button", { name: "저장" }).click();
  await expect(dialog.getByText(/다른 곳에서 먼저 수정했습니다/)).toBeVisible();
  expect((await call(request, "GET", `/api/environments/${environmentId}`)).variables).toEqual({
    userId: "42",
    locale: "ko-KR",
  });

  // 다시 열면 최신 내용과 version으로 저장
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await chooseAction(page, "환경 dev 작업", "수정");
  // 다른 사람이 추가한 변수까지 보임 (행 순서는 화면 정렬을 따름)
  const names = [
    await dialog.getByLabel("변수 1 이름").inputValue(),
    await dialog.getByLabel("변수 2 이름").inputValue(),
  ];
  expect(names.toSorted()).toEqual(["locale", "userId"]);
  await dialog.getByLabel(`변수 ${names.indexOf("userId") + 1} 값`).fill("7");
  await dialog.getByRole("button", { name: "저장" }).click();
  await expect(dialog).toBeHidden();
  expect((await call(request, "GET", `/api/environments/${environmentId}`)).variables).toEqual({
    userId: "7",
    locale: "ko-KR",
  });
});
