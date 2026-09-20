import Link from "next/link";

import { Badge } from "@/components/ui/badge";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

const screens = [
  { title: "실행 이력", description: "스케줄·수동 실행 결과 목록과 통과·실패·오류 요약", href: "/runs" },
  { title: "결과 상세", description: "케이스별 판정 규칙 결과와 요청·응답 원문", href: "/runs" },
  {
    title: "프로젝트·스위트",
    description: "프로젝트·환경·스위트 등록·편집, 케이스 목록과 즉시 실행",
    href: "/projects",
  },
  { title: "케이스 편집", description: "요청 정의와 판정 규칙 등록·편집 (스위트 화면에서 이동)", href: "/projects" },
  { title: "스케줄", description: "cron 설정과 다음 실행 시각 확인, 활성·비활성" },
];

export default function Home() {
  return (
    <div className="space-y-8">
      <div className="space-y-2">
        <h1 className="text-2xl font-semibold tracking-tight">Sentinel</h1>
        <p className="text-muted-foreground">API 검증의 정시 자동 실행 및 결과 이력 공유 도구</p>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        {screens.map((screen) => {
          const card = (
            <Card className={screen.href ? "h-full transition-colors hover:bg-muted/50" : "h-full"}>
              <CardHeader>
                <div className="flex items-center justify-between gap-2">
                  <CardTitle>{screen.title}</CardTitle>
                  <Badge variant={screen.href ? "default" : "secondary"}>{screen.href ? "사용 가능" : "준비 중"}</Badge>
                </div>
                <CardDescription>{screen.description}</CardDescription>
              </CardHeader>
            </Card>
          );
          return screen.href ? (
            <Link key={screen.title} href={screen.href}>
              {card}
            </Link>
          ) : (
            <div key={screen.title}>{card}</div>
          );
        })}
      </div>
    </div>
  );
}
