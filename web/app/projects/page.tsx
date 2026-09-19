import { Plus } from "lucide-react";
import Link from "next/link";

import { ProjectFields } from "@/components/catalog/entity-fields";
import { FormDialog } from "@/components/catalog/form-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { createProject } from "@/lib/actions/catalog";
import { apiGet } from "@/lib/api/server";
import type { ProjectItem } from "@/lib/api/types";

export const metadata = { title: "프로젝트 · Sentinel" };

export default async function ProjectsPage() {
  const projects = await apiGet<ProjectItem[]>("/api/projects");

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold tracking-tight">프로젝트</h1>
          <p className="text-sm text-muted-foreground">검증 대상 서비스 단위. 환경과 스위트를 묶음</p>
        </div>
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
      </div>

      {projects.length === 0 ? (
        <p className="rounded-lg border border-dashed p-10 text-center text-sm text-muted-foreground">
          등록된 프로젝트가 없습니다
        </p>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2">
          {projects.map((project) => (
            <Link key={project.id} href={`/projects/${project.id}`}>
              <Card className="h-full transition-colors hover:bg-muted/50">
                <CardHeader>
                  <CardTitle>{project.name}</CardTitle>
                  <CardDescription>{project.description ?? "설명 없음"}</CardDescription>
                </CardHeader>
              </Card>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
