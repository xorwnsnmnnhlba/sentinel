import { accessibilityViolations, chooseAction, expect, mainMenu, test } from "./fixtures";

/**
 * 프로젝트별 알림 채널. Webhook 지정 → 연결 확인 → 변경 → 기본 채널로 되돌리기
 * 받는 쪽은 api 자신 (POST를 받지 않는 경로라 오류 응답), 클라우드 메타데이터 주소는 보내지 않음
 */
const api = process.env.E2E_API_URL ?? "http://localhost:18080";
const projectName = `notify-${Date.now()}`;

let projectId = 0;

test.beforeAll(async ({ request }) => {
  const session = await request.post(`${api}/api/sessions`, {
    data: { email: "admin@sentinel.test", password: "e2e-admin-password" },
  });
  const headers = { Authorization: `Bearer ${(await session.json()).token}` };
  const created = await request.post(`${api}/api/projects`, { headers, data: { name: projectName } });
  expect(created.ok()).toBe(true);
  projectId = ((await created.json()) as { id: number }).id;
});

test.beforeEach(async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(mainMenu(page).getByRole("link", { name: "프로젝트" })).toBeVisible();
});

test("프로젝트 Webhook을 지정하고 연결을 확인한 뒤 기본 채널로 되돌림", async ({ page }) => {
  await page.goto(`/projects/${projectId}`);
  await expect(page.getByRole("heading", { name: projectName, level: 1 })).toBeVisible();
  await expect(page.getByText(/서버 기본 채널로 보냅니다/)).toBeVisible();

  await page.getByRole("button", { name: "Webhook 지정" }).click();
  const register = page.getByRole("dialog", { name: "Webhook 지정" });
  await register.getByLabel("Webhook 주소").fill("http://e2e-api:8080/api/invites/webhook-check");
  await register.getByRole("button", { name: "저장" }).click();
  await expect(register).toBeHidden();
  await expect(page.getByText(/이 프로젝트 전용 Webhook으로 보냅니다/)).toBeVisible();
  // 저장한 주소는 화면에 없음
  await expect(page.getByText("webhook-check")).toHaveCount(0);

  await page.getByRole("button", { name: "테스트 알림 보내기" }).click();
  await expect(page.getByRole("status").filter({ hasText: "채널이 받지 않았습니다 (HTTP" })).toBeVisible();
  expect(await accessibilityViolations(page)).toEqual([]);

  // 바꿀 때도 저장된 주소를 불러오지 않음
  await chooseAction(page, "알림 채널 작업", "Webhook 변경");
  const change = page.getByRole("dialog", { name: "Webhook 변경" });
  await expect(change.getByLabel("Webhook 주소")).toHaveValue("");
  await change.getByLabel("Webhook 주소").fill("http://169.254.169.254/latest/meta-data");
  await change.getByRole("button", { name: "저장" }).click();
  await expect(change).toBeHidden();
  await page.getByRole("button", { name: "테스트 알림 보내기" }).click();
  await expect(page.getByRole("status").filter({ hasText: "내부·메타데이터 주소라 보내지 않았습니다" })).toBeVisible();

  await chooseAction(page, "알림 채널 작업", "기본 채널로 되돌리기");
  await page.getByRole("alertdialog").getByRole("button", { name: "되돌리기" }).click();
  await expect(page.getByText(/서버 기본 채널로 보냅니다/)).toBeVisible();
  await expect(page.getByRole("button", { name: "테스트 알림 보내기" })).toHaveCount(0);
});
