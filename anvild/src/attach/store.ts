import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import type { AttachmentRef } from "@protocol";
import { newId } from "../util/ids";

const EXT: Record<string, string> = {
  "image/png": "png",
  "image/jpeg": "jpg",
  "image/gif": "gif",
  "image/webp": "webp",
};

/**
 * Per-session attachment store (arch §6.5). Pasted/dropped images are written under
 * `~/.anvil/sessions/<id>/attachments/` with a small `.json` sidecar (so serving survives a
 * daemon restart). The driver base64-loads them into the user message as image blocks.
 */
export class AttachmentStore {
  constructor(private readonly stateDir: string) {}

  private dir(sessionId: string): string {
    const d = join(this.stateDir, "sessions", sessionId, "attachments");
    mkdirSync(d, { recursive: true });
    return d;
  }

  add(sessionId: string, name: string, mediaType: string, dataBase64: string): AttachmentRef {
    const id = newId("att");
    const ext = EXT[mediaType] ?? "bin";
    const dir = this.dir(sessionId);
    const binPath = join(dir, `${id}.${ext}`);
    writeFileSync(binPath, Buffer.from(dataBase64, "base64"));
    writeFileSync(join(dir, `${id}.json`), JSON.stringify({ mediaType, name, ext }));
    return { id, kind: mediaType.startsWith("image/") ? "image" : "file", name, path: binPath };
  }

  private resolve(sessionId: string, id: string): { binPath: string; mediaType: string; name: string } | undefined {
    const metaPath = join(this.dir(sessionId), `${id}.json`);
    if (!existsSync(metaPath)) return undefined;
    const meta = JSON.parse(readFileSync(metaPath, "utf8")) as { mediaType: string; name: string; ext: string };
    return { binPath: join(this.dir(sessionId), `${id}.${meta.ext}`), mediaType: meta.mediaType, name: meta.name };
  }

  ref(sessionId: string, id: string): AttachmentRef | undefined {
    const r = this.resolve(sessionId, id);
    if (!r) return undefined;
    return { id, kind: r.mediaType.startsWith("image/") ? "image" : "file", name: r.name, path: r.binPath };
  }

  /** For the REST GET endpoint. */
  bytes(sessionId: string, id: string): { mediaType: string; path: string } | undefined {
    const r = this.resolve(sessionId, id);
    return r && existsSync(r.binPath) ? { mediaType: r.mediaType, path: r.binPath } : undefined;
  }

  /** For feeding the agent — base64 + media type for an image content block. */
  loadBase64(sessionId: string, id: string): { mediaType: string; data: string } | undefined {
    const r = this.resolve(sessionId, id);
    if (!r || !existsSync(r.binPath)) return undefined;
    return { mediaType: r.mediaType, data: readFileSync(r.binPath).toString("base64") };
  }
}
