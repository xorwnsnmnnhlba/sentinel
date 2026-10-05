import { accessibilityViolations, expect, mainMenu, test } from "./fixtures";

/**
 * 알림 발송 이력. 프로젝트 Webhook으로 연결 확인 알림을 보낸 뒤(받는 쪽 오류 응답) 이력에서 결과 확인
 */
const api = process.env.E2E_API_URL ?? "http://localhost:18080";
const stamp = Date.now();
const projectName = `deliveries-${stamp}`;

let projectId = 0;

test.beforeAll(async ({ request }) => {
  const session = await request.post(`${api}/api/sessions`, {
    data: { email: "admin@sentinel.test", password: "e2e-admin-password" },
  });
  const headers = { Authorization: `Bearer ${(await session.json()).token}` };
  const project = await request.post(`${api}/api/projects`, { headers, data: { name: projectName } });
  projectId = ((await project.json()) as { id: number }).id;
  // 받는 쪽이 오류로 응답하는 주소 (주소는 화면·이력에 남지 않음)
  await request.patch(`${api}/api/projects/${projectId}`, {
    headers,
    data: { webhookUrl: "http://e2e-api:8080/api/invites/delivery-check" },
  });
  const sent = await request.post(`${api}/api/projects/${projectId}/test-notifications`, { headers });
  expect(sent.ok()).toBe(true);
});

test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(mainMenu(page).getByRole("link", { name: "알림 이력" })).toBeVisible();
});

test("보내지 못한 알림을 프로젝트·실패로 좁혀 결과와 확인할 점을 봄", async ({ page }) => {
  await mainMenu(page).getByRole("link", { name: "알림 이력" }).click();
  await expect(page.getByRole("heading", { name: "알림 이력", level: 1 })).toBeVisible();

  await page.getByLabel("프로젝트 Webhook").selectOption({ label: projectName });
  await page.getByLabel("보내지 못한 것만").check();
  await page.getByRole("button", { name: "조회" }).click();
  await expect(page).toHaveURL(new RegExp(`projectId=${projectId}.*failed=true`));

  const row = page
    .getByRole("table", { name: "알림 발송 이력" })
    .getByRole("row", { name: new RegExp(`${projectName} 알림 연결 확인`) });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText("안내");
  await expect(row).toContainText(`${projectName} · 텍스트`);
  await expect(row).toContainText(/HTTP 4\d\d/);
  // 주소는 이력에 없음
  await expect(page.getByText("delivery-check")).toHaveCount(0);
  expect(await accessibilityViolations(page)).toEqual([]);

  await page.getByRole("link", { name: "초기화" }).click();
  await expect(page).toHaveURL(/\/notifications$/);
});

test("프로젝트 화면의 알림 채널에서 그 프로젝트의 발송 이력으로 이동", async ({ page }) => {
  await page.goto(`/projects/${projectId}`);
  await page.getByRole("link", { name: "발송 이력 보기" }).click();

  await expect(page).toHaveURL(new RegExp(`/notifications\\?projectId=${projectId}$`));
  await expect(page.getByLabel("프로젝트 Webhook")).toHaveValue(String(projectId));
  await expect(page.getByRole("row", { name: new RegExp(`${projectName} 알림 연결 확인`) })).toHaveCount(1);
});
