import { expect, test, type Page, chooseAction, mainMenu, sidebar } from "./fixtures";

/**
 * 초대 → 계정 생성 → 비밀번호 변경 → 관리자 재설정 링크 → 사용자 삭제
 * 메일 발송이 없으므로 화면에 표시된 링크를 그대로 사용
 */
test.describe.configure({ mode: "serial" });

const admin = { email: "admin@sentinel.test", password: "e2e-admin-password" };
const stamp = Date.now();
const member = {
  email: `member-${stamp}@sentinel.test`,
  name: "초대된 구성원",
  password: `member-password-${stamp}`,
  changed: `changed-password-${stamp}`,
  reset: `reset-password-${stamp}`,
};

let inviteLink: string;

/** 로그인 후 화면 이동까지 대기. 실패를 확인하는 경우에는 submitLogin 사용 */
async function login(page: Page, email: string, password: string) {
  await submitLogin(page, email, password);
  await page.waitForURL((url) => !url.pathname.startsWith("/login"));
}

async function submitLogin(page: Page, email: string, password: string) {
  await page.goto("/login");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호", { exact: true }).fill(password);
  await page.getByRole("button", { name: "로그인" }).click();
}

test("관리자가 초대 링크를 발급", async ({ page }) => {
  await login(page, admin.email, admin.password);
  await mainMenu(page).getByRole("link", { name: "사용자" }).click();

  await expect(page.getByRole("heading", { name: "사용자" })).toBeVisible();
  await page.getByRole("button", { name: "사용자 초대" }).click();
  await page.getByLabel("이메일").fill(member.email);
  await page.getByRole("button", { name: "초대 링크 발급" }).click();

  const link = page.getByLabel("발급된 링크");
  await expect(link).toBeVisible();
  inviteLink = String(await link.inputValue());
  expect(inviteLink).toContain("/invites/");
});

test("초대 링크로 계정을 만들면 바로 로그인 상태", async ({ page }) => {
  await page.goto(inviteLink);

  await expect(page.getByText(member.email)).toBeVisible();
  await page.getByLabel("이름").fill(member.name);
  await page.getByLabel("비밀번호", { exact: true }).fill(member.password);
  await page.getByLabel("비밀번호 확인").fill(member.password);
  await page.getByRole("button", { name: "계정 만들기" }).click();

  await expect(page).toHaveURL(/\/$/);
  await expect(sidebar(page).getByText(member.email)).toBeVisible();
  // 구성원에게는 사용자 메뉴 미표시
  await expect(mainMenu(page).getByRole("link", { name: "사용자" })).toHaveCount(0);
});

test("사용한 초대 링크는 다시 쓸 수 없음", async ({ page }) => {
  await page.goto(inviteLink);

  await expect(page.getByRole("heading", { name: "링크를 사용할 수 없습니다" })).toBeVisible();
});

test("본인이 비밀번호를 바꾸면 다시 로그인해야 함", async ({ page }) => {
  await login(page, member.email, member.password);
  await sidebar(page).getByRole("link", { name: member.email }).click();

  await page.getByLabel("현재 비밀번호").fill(member.password);
  await page.getByLabel("새 비밀번호", { exact: true }).fill(member.changed);
  await page.getByLabel("새 비밀번호 확인").fill(member.changed);
  await page.getByRole("button", { name: "비밀번호 변경" }).click();

  await expect(page).toHaveURL(/\/login\?changed=1$/);
  await expect(page.getByText("비밀번호를 변경했습니다. 새 비밀번호로 로그인해 주세요")).toBeVisible();

  await login(page, member.email, member.changed);
  await expect(sidebar(page).getByText(member.email)).toBeVisible();
});

test("관리자가 발급한 재설정 링크로 비밀번호를 다시 설정", async ({ page }) => {
  await login(page, admin.email, admin.password);
  await page.goto("/users");
  await chooseAction(page, `${member.email} 작업`, "비밀번호 재설정 링크");
  await page.getByRole("button", { name: "링크 발급", exact: true }).click();
  const resetLink = String(await page.getByLabel("발급된 링크").inputValue());

  await page.goto(resetLink);
  await page.getByLabel("새 비밀번호", { exact: true }).fill(member.reset);
  await page.getByLabel("새 비밀번호 확인").fill(member.reset);
  await page.getByRole("button", { name: "비밀번호 설정" }).click();

  await expect(page).toHaveURL(/\/login\?changed=1$/);
  await login(page, member.email, member.reset);
  await expect(sidebar(page).getByText(member.email)).toBeVisible();
});

test("관리자가 사용자를 삭제하면 로그인할 수 없음", async ({ page }) => {
  await login(page, admin.email, admin.password);
  await page.goto("/users");
  await chooseAction(page, `${member.email} 작업`, "삭제");
  await page.getByRole("alertdialog").getByRole("button", { name: "삭제" }).click();

  await expect(page.getByRole("row", { name: new RegExp(member.email) })).toHaveCount(0);

  await submitLogin(page, member.email, member.reset);
  await expect(page.locator('[role="alert"]:not(#__next-route-announcer__)')).toHaveText(
    "이메일 또는 비밀번호가 올바르지 않습니다",
  );
});
