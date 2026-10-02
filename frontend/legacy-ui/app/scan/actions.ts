"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { createScan } from "@/lib/api";

export async function startScan(formData: FormData): Promise<void> {
  const value = formData.get("repository");
  const repository = typeof value === "string" ? value : "";
  if (!repository) {
    redirect("/scan?error=Select+a+repository+before+starting+a+scan.");
  }

  const result = await createScan(repository);
  if (!result.ok) {
    redirect(`/scan?repository=${encodeURIComponent(repository)}&error=${encodeURIComponent(result.error)}`);
  }

  revalidatePath("/scan");
  const scan = result.data.scan;
  redirect(
    `/scan?repository=${encodeURIComponent(repository)}&scanId=${encodeURIComponent(scan.id)}&status=${encodeURIComponent(scan.status)}`,
  );
}
