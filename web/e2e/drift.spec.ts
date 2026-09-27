import { accessibilityViolations, expect, test } from "./fixtures";

/**
 * 응답 구조 변화 (스키마 드리프트 2단계). 같은 케이스·환경의 이전 실행 지문을 기준선으로 비교
 * 객체를 돌려주던 케이스를 배열을 돌려주는 경로로 바꿔 호환이 깨지는 변화(BREAKING)를 만듦
 */
const api = process.env.E2E_API_URL ?? "http://localhost:18080";

let runId = 0;
const caseName = `구조 변화 확인 ${Date.now()}`;

test.beforeAll(async ({ request }) => {
  const session = await request.post(`${api}/api/sessions`, {
    data: { email: "admin@sentinel.test", password: "e2e-admin-password" },
  });
  const token = (await session.json()).token as string;
  const headers = { Authorization: `Bearer ${token}` };
  const send = async (method: "post" | "patch", path: string, data: object) => {
    const response = await request[method](`${api}${path}`, { headers, data });
    expect(response.ok(), `${method} ${path} ${response.status()}`).toBe(true);
    return (await response.json()) as { id: number };
  };

  const project = await send("post", "/api/projects", { name: `drift-${Date.now()}` });
  // 토큰은 테스트용 세션 값. 케이스가 api 자신을 호출할 때 인증 헤더로 사용
  const environment = await send("post", "/api/environments", {
    projectId: project.id,
    name: "self",
    baseUrl: "http://e2e-api:8080",
    variables: { token },
  });
  const suite = await send("post", "/api/suites", { projectId: project.id, name: "drift" });
  const testCase = await send("post", "/api/cases", {
    suiteId: suite.id,
    name: caseName,
    request: { method: "GET", path: "/api/invites/unknown", headers: {}, body: null },
    assertions: [{ type: "statusCode", anyOf: ["404"] }],
  });
  // 기준선: 객체 응답 3회 (판정에 필요한 최소 관측 수)
  for (let i = 0; i < 3; i++) {
    await send("post", "/api/runs", { caseId: testCase.id, environmentId: environment.id });
  }
  // 같은 케이스를 배열을 돌려주는 경로로 변경
  await send("patch", `/api/cases/${testCase.id}`, {
    request: {
      method: "GET",
      path: "/api/projects",
      headers: { Authorization: ["Bearer {{token}}"] },
      body: null,
    },
    assertions: [],
  });
  runId = (await send("post", "/api/runs", { caseId: testCase.id, environmentId: environment.id })).id;
});

test("구조가 바뀐 케이스를 표시하고 펼쳐서 변화를 보여 줌", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill("admin@sentinel.test");
  await page.getByLabel("비밀번호").fill("e2e-admin-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).toHaveURL(/\/$/);

  await page.goto(`/runs/${runId}`);
  const trigger = page.getByRole("button", { name: new RegExp(caseName) });
  // 판정은 통과지만 호환이 깨지는 변화라 목록에 표시하고 펼친 상태
  await expect(trigger).toContainText("통과");
  await expect(trigger).toContainText("구조 변화 1");
  await expect(trigger).toHaveAttribute("aria-expanded", "true");

  await page.getByRole("tab", { name: "구조" }).click();
  await expect(page.getByText("이전 실행 3회를 기준으로 비교한 변화입니다")).toBeVisible();
  const changes = page.getByRole("list", { name: "구조 변화" }).getByRole("listitem");
  await expect(changes).toHaveCount(1);
  await expect(changes.first()).toContainText("호환 깨짐");
  await expect(changes.first()).toContainText("타입 변경");
  await expect(changes.first()).toContainText("object → array");
  expect(await accessibilityViolations(page)).toEqual([]);
});
