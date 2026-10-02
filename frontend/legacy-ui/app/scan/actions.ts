"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { createScan } from "@/lib/api";

export async function startScan(): Promise<void> {
  const result = await createScan();
  if (!result.ok) {
    redirect(`/scan?error=${encodeURIComponent(result.error)}`);
  }

  revalidatePath("/scan");
  const scan = result.data.scan;
  redirect(`/scan?scanId=${encodeURIComponent(scan.id)}&status=${encodeURIComponent(scan.status)}`);
}
