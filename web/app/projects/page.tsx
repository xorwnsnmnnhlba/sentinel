import { Plus } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import Link from "next/link";

import { ProjectFields } from "@/components/catalog/entity-fields";
import { FormDialog } from "@/components/catalog/form-dialog";
import { Panel, PanelEmpty } from "@/components/catalog/section";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { createProject } from "@/lib/actions/catalog";
import { apiGet } from "@/lib/api/server";
import type { ProjectItem } from "@/lib/api/types";

export const metadata = { title: "프로젝트 · Sentinel" };

export default async function ProjectsPage() {
  const projects = await apiGet<ProjectItem[]>("/api/projects");

  return (
    <div className="space-y-4">
      <PageHeader
        title="프로젝트"
        actions={
          <FormDialog
            trigger={
              <Button>
                <Plus /> 새 프로젝트
              </Button>
            }
            title="새 프로젝트"
            submitLabel="등록"
            action={createProject}
          >
            <ProjectFields />
          </FormDialog>
        }
      />

      <Panel title="프로젝트" count={projects.length}>
        {projects.length === 0 ? (
          <PanelEmpty message="등록된 프로젝트가 없습니다" />
        ) : (
          <Table aria-label="프로젝트 목록">
            <TableHeader>
              <TableRow>
                <TableHead>이름</TableHead>
                <TableHead>설명</TableHead>
                <TableHead>알림 채널</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {projects.map((project) => (
                <TableRow key={project.id} className="relative">
                  <TableCell className="font-medium">
                    {/* 행 전체를 클릭 영역으로 사용 */}
                    <Link href={`/projects/${project.id}`} className="after:absolute after:inset-0 hover:underline">
                      {project.name}
                    </Link>
                  </TableCell>
                  <TableCell className="whitespace-normal text-muted-foreground">
                    {project.description ?? "-"}
                  </TableCell>
                  <TableCell className="text-xs text-muted-foreground">
                    {project.webhookConfigured ? "프로젝트 Webhook" : "기본 채널"}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Panel>
    </div>
  );
}
