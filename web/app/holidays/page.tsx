import { ChevronLeft, ChevronRight, Plus } from "lucide-react";
import Link from "next/link";

import { ActionMenu, type ActionMenuItem } from "@/components/catalog/action-menu";
import { DeleteDialog } from "@/components/catalog/delete-dialog";
import { TextField } from "@/components/catalog/field";
import { FormDialog } from "@/components/catalog/form-dialog";
import { Panel, PanelEmpty } from "@/components/catalog/section";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { createHoliday, deleteHoliday } from "@/lib/actions/holidays";
import { apiGet } from "@/lib/api/server";
import type { AuthenticatedUser, Holiday } from "@/lib/api/types";
import { historyHref } from "@/lib/change-log";
import { kstToday, MAX_NAME_LENGTH, upcomingLabel, weekday, yearOf } from "@/lib/holiday";

export const metadata = { title: "휴일 · Sentinel" };

/**
 * 휴일. 휴일 건너뛰기를 켠 스케줄은 이날 실행하지 않음 (api/README.md "휴일")
 * 조회는 모든 사용자(스케줄이 왜 실행되지 않았는지 확인), 등록·삭제는 관리자만
 */
export default async function HolidaysPage(props: PageProps<"/holidays">) {
  const searchParams = await props.searchParams;
  const today = kstToday(new Date());
  const year = yearOf(single(searchParams.year), today);
  const [holidays, me] = await Promise.all([
    apiGet<Holiday[]>(`/api/holidays?year=${year}`),
    apiGet<AuthenticatedUser>("/api/sessions/current"),
  ]);
  const isAdmin = me.role === "ADMIN";

  return (
    <div className="space-y-4">
      <PageHeader
        title="휴일"
        actions={
          isAdmin && (
            <FormDialog
              trigger={
                <Button>
                  <Plus /> 휴일 추가
                </Button>
              }
              title="휴일 추가"
              description="휴일 건너뛰기를 켠 스케줄은 이날 실행하지 않습니다."
              submitLabel="등록"
              action={createHoliday}
            >
              <TextField name="date" label="날짜 (KST)" type="date" required />
              <TextField
                name="name"
                label="이름"
                required
                maxLength={MAX_NAME_LENGTH}
                placeholder="예: 추석, 창립기념일"
              />
            </FormDialog>
          )
        }
      />

      <Panel
        title={`${year}년`}
        count={holidays.length}
        meta={upcomingLabel(holidays, today)}
        action={
          <div className="flex gap-1">
            <YearLink year={year - 1} icon="previous" />
            {year !== Number(today.slice(0, 4)) && (
              <Button asChild variant="outline" size="sm">
                <Link href="/holidays">올해</Link>
              </Button>
            )}
            <YearLink year={year + 1} icon="next" />
          </div>
        }
      >
        {holidays.length === 0 ? (
          <PanelEmpty message={`${year}년에 등록된 휴일이 없습니다`} />
        ) : (
          <Table aria-label={`${year}년 휴일`}>
            <TableHeader>
              <TableRow>
                <TableHead className="w-44">날짜</TableHead>
                <TableHead>이름</TableHead>
                <TableHead className="w-12">
                  <span className="sr-only">작업</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {holidays.map((holiday) => {
                const past = holiday.date < today;
                return (
                  <TableRow key={holiday.id} className={past ? "text-muted-foreground" : undefined}>
                    <TableCell className="font-mono text-xs">
                      <div className="flex items-center gap-2">
                        {holiday.date} ({weekday(holiday.date)}){holiday.date === today && <Badge>오늘</Badge>}
                      </div>
                    </TableCell>
                    <TableCell className="font-medium whitespace-normal">{holiday.name}</TableCell>
                    <TableCell className="text-right">
                      <ActionMenu
                        label={`휴일 ${holiday.date} ${holiday.name} 작업`}
                        items={holidayMenu(holiday.id, isAdmin)}
                      >
                        {isAdmin && (
                          <DeleteDialog
                            menuId="delete"
                            title={`휴일 "${holiday.date} ${holiday.name}" 삭제`}
                            description="이날도 스케줄이 실행됩니다."
                            action={deleteHoliday.bind(null, holiday.id)}
                          />
                        )}
                      </ActionMenu>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </Panel>
    </div>
  );
}

function YearLink({ year, icon }: { year: number; icon: "previous" | "next" }) {
  const Icon = icon === "previous" ? ChevronLeft : ChevronRight;
  return (
    <Button asChild variant="outline" size="icon-sm">
      <Link href={`/holidays?year=${year}`} aria-label={`${year}년`}>
        <Icon aria-hidden />
      </Link>
    </Button>
  );
}

/** 삭제는 관리자만 */
function holidayMenu(holidayId: number, isAdmin: boolean): ActionMenuItem[] {
  const history: ActionMenuItem = {
    id: "history",
    label: "변경 이력",
    icon: "history",
    href: historyHref("HOLIDAY", holidayId),
  };
  return isAdmin ? [history, { id: "delete", label: "삭제", icon: "delete", destructive: true }] : [history];
}

function single(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}
