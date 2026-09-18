import { api, ApiError } from "./api";
import type { SavedRecipient } from "./types";

export type { SavedRecipient };

export type SaveRecipientResult =
  | { kind: "created"; recipients: SavedRecipient[] }
  | { kind: "updated"; recipients: SavedRecipient[] }
  | { kind: "label-taken"; message: string };

/**
 * A server-side, per-user address book — account-service's own
 * `/v1/recipients` endpoints (see the backend's own `RecipientService`),
 * NOT localStorage. An earlier version stored this in the browser; that
 * was replaced because it didn't survive a new device or browser, didn't
 * sync across sessions, and was scoped to the browser ORIGIN rather than
 * to which Lynx account was actually logged in — see
 * docs/html/utils/saved-recipients-flows.html for the full story.
 */
export async function listRecipients(): Promise<SavedRecipient[]> {
  return api.get<SavedRecipient[]>("/v1/recipients");
}

/**
 * The backend enforces the SAME two collision rules a caller needs to
 * react to — this just translates its response into the same three-way
 * result shape the UI already expects, so the calling page's logic reads
 * identically to when this was client-side:
 *   - same recipientUserId already saved → the backend treats it as an
 *     update (200, new label) — surfaced here as "updated"
 *   - a DIFFERENT recipientUserId already owns this exact label → the
 *     backend refuses with 409 Conflict — surfaced here as "label-taken",
 *     never silently overwritten
 */
export async function saveRecipient(label: string, recipientUserId: string): Promise<SaveRecipientResult> {
  try {
    const before = await listRecipients();
    const existed = before.some((r) => r.recipientUserId === recipientUserId);
    await api.post<SavedRecipient>("/v1/recipients", { label, recipientUserId });
    const after = await listRecipients();
    return { kind: existed ? "updated" : "created", recipients: after };
  } catch (err) {
    if (err instanceof ApiError && err.status === 409) {
      return { kind: "label-taken", message: err.message };
    }
    throw err;
  }
}

export async function removeRecipient(id: string): Promise<SavedRecipient[]> {
  await api.del<void>(`/v1/recipients/${id}`);
  return listRecipients();
}
