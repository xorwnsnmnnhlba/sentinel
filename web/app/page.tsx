import { Badge } from "@/components/ui/badge";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

const screens = [
  { title: "실행 이력", description: "스케줄·수동 실행 결과 목록과 통과·실패·오류 요약" },
  { title: "결과 상세", description: "케이스별 판정 규칙 결과와 요청·응답 원문" },
  { title: "케이스·스위트", description: "요청 정의와 판정 규칙 등록·편집, 스위트 구성" },
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
        {screens.map((screen) => (
          <Card key={screen.title}>
            <CardHeader>
              <div className="flex items-center justify-between gap-2">
                <CardTitle>{screen.title}</CardTitle>
                <Badge variant="secondary">준비 중</Badge>
              </div>
              <CardDescription>{screen.description}</CardDescription>
            </CardHeader>
          </Card>
        ))}
      </div>
    </div>
  );
}
