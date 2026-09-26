import { Plus } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import Link from "next/link";

import { ProjectFields } from "@/components/catalog/entity-fields";
import { FormDialog } from "@/components/catalog/form-dialog";
import { Empty } from "@/components/catalog/section";
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
      <PageHeader
        title="프로젝트"
        description="검증 대상 서비스 단위. 환경과 스위트를 묶음"
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

      {projects.length === 0 ? (
        <Empty message="등록된 프로젝트가 없습니다" />
      ) : (
        <div className="grid gap-4 sm:grid-cols-2">
          {projects.map((project) => (
            <Link key={project.id} href={`/projects/${project.id}`}>
              <Card className="h-full transition-colors hover:border-brand-violet/40 hover:bg-accent/40">
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
