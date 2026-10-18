import type { EnvironmentItem, ProjectItem, ScheduleDetail, SuiteItem } from "@/lib/api/types";

/**
 * 홈의 "처음 시작하기". 등록된 것을 보고 스케줄 실행까지 남은 단계와 각 단계를 할 화면 (docs/manual.md "처음 시작하기")
 * 스케줄이 하나라도 생기면 핵심 흐름(점검 등록 → 스케줄 실행 → 알림)이 갖춰졌으므로 표시하지 않음
 */

export interface StartStep {
  title: string;
  /** 어디서 무엇을 하는지 */
  detail: string;
  done: boolean;
  /** 할 화면. 끝났거나 앞 단계가 남아 아직 할 수 없으면 null */
  href: string | null;
}

export interface StartState {
  projects: ProjectItem[];
  environments: EnvironmentItem[];
  suites: SuiteItem[];
  schedules: ScheduleDetail[];
  /** 서버 기본 알림 채널 주소가 있음 */
  defaultChannel: boolean;
}

export function showGettingStarted(state: StartState): boolean {
  return state.schedules.length === 0;
}

export function startSteps({ projects, environments, suites, schedules, defaultChannel }: StartState): StartStep[] {
  // 이어서 할 곳: 환경이 있는 프로젝트 우선. 스케줄은 케이스와 환경이 같은 프로젝트에 있는 스위트에서만 만들 수 있음
  const projectWithEnvironment = projects.find((project) =>
    environments.some((environment) => environment.project.id === project.id),
  );
  const project = projectWithEnvironment ?? projects[0];
  const suitesOfProject = suites.filter((suite) => suite.project.id === project?.id);
  const suite = suitesOfProject[0] ?? suites[0];
  const runnableSuite = suites.find(
    (item) => item.caseCount > 0 && environments.some((environment) => environment.project.id === item.project.id),
  );

  const hasProject = projects.length > 0;
  const hasEnvironment = environments.length > 0;
  const hasSuite = suites.length > 0;
  const hasCase = suites.some((item) => item.caseCount > 0);
  const hasChannel = defaultChannel || projects.some((item) => item.webhookConfigured);
  const projectHref = project ? `/projects/${project.id}` : null;

  const step = (title: string, detail: string, done: boolean, href: string | null): StartStep => ({
    title,
    detail,
    done,
    href: done ? null : href,
  });

  return [
    step("프로젝트 등록", '"새 프로젝트"로 점검할 서비스 단위 등록', hasProject, "/projects"),
    step("환경 추가", '프로젝트 화면 "환경 추가"에서 기본 URL과 변수·시크릿', hasEnvironment, projectHref),
    step("스위트 추가", '프로젝트 화면 "스위트 추가"로 함께 실행할 케이스 묶음', hasSuite, projectHref),
    step(
      "케이스 추가",
      '스위트 화면 "케이스 추가" 또는 "문서 가져오기"(OpenAPI·Postman), "등록 후 실행"으로 바로 확인',
      hasCase,
      suite ? `/suites/${suite.id}` : null,
    ),
    step(
      "알림 채널 확인",
      '프로젝트 화면 맨 아래 "알림 채널"에서 Webhook 지정 후 "테스트 알림 보내기" (지정하지 않으면 서버 기본 채널)',
      hasChannel,
      projectHref,
    ),
    step(
      "스케줄 추가",
      '스위트 화면 "스케줄"에서 환경과 cron (예: 10 9 * * 1-5 평일 09:10). 이후 자동 실행, 결과가 바뀌면 알림',
      schedules.length > 0,
      runnableSuite ? `/suites/${runnableSuite.id}` : null,
    ),
  ];
}
