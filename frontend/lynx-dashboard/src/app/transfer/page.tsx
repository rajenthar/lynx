"use client";

import { useEffect, useState } from "react";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import { api, ApiError } from "@/lib/api";
import type { Account, TransferAccepted, TransferStatus } from "@/lib/types";
import { ErrorText, SubmitButton } from "@/components/AuthCard";
import { AnimatedCheck, PlusIcon, StarIcon, TrashIcon, TransferIcon } from "@/components/Icons";
import { listRecipients, removeRecipient, saveRecipient, type SavedRecipient } from "@/lib/recipients";

function TransferContent() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [currencies, setCurrencies] = useState<string[]>([]);
  const [fromCurrency, setFromCurrency] = useState("");
  const [toCurrency, setToCurrency] = useState("");
  const [recipientUserId, setRecipientUserId] = useState("");
  const [recipientLabel, setRecipientLabel] = useState("");
  const [amount, setAmount] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [status, setStatus] = useState<TransferStatus | null>(null);
  const [saved, setSaved] = useState<SavedRecipient[]>([]);
  const [saveNotice, setSaveNotice] = useState<{ kind: "ok" | "warn"; text: string } | null>(null);

  function loadAccounts() {
    return api.get<Account[]>("/v1/accounts").then((accs) => {
      setAccounts(accs);
      return accs;
    });
  }

  useEffect(() => {
    loadAccounts().then((accs) => {
      if (accs.length > 0) {
        setFromCurrency(accs[0].currency);
      }
    });
    listRecipients().then(setSaved);
    api.get<string[]>("/v1/accounts/currencies").then((list) => {
      setCurrencies(list);
      setToCurrency((current) => current || list[0] || "");
    });
  }, []);

  // Once the saga actually settles, the sender's own balance has changed
  // but — same reasoning as deposit/page.tsx's own polling — only
  // asynchronously, once the ledger event comes back through
  // account-service's Kafka projection. A few retries here is enough to
  // catch up in the common case; if it's still stale after that, the
  // account list's own next natural refetch (e.g. revisiting /accounts)
  // will show the real number anyway.
  useEffect(() => {
    if (status?.status !== "SETTLED") return;
    let cancelled = false;
    let attempts = 0;
    const before = accounts.find((a) => a.currency === fromCurrency)?.available;

    function poll() {
      if (cancelled || attempts >= 6) return;
      attempts += 1;
      loadAccounts().then((accs) => {
        if (cancelled) return;
        const after = accs.find((a) => a.currency === fromCurrency)?.available;
        if (after !== before) return;
        setTimeout(poll, 1500);
      });
    }
    const timer = setTimeout(poll, 1500);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status?.status]);

  useEffect(() => {
    if (!status || status.status === "SETTLED" || status.status === "FAILED" || status.status === "RELEASED") {
      return;
    }
    const timer = setTimeout(async () => {
      try {
        const updated = await api.get<TransferStatus>(`/v1/transfers/${status.sagaId}`);
        setStatus(updated);
      } catch {
        // transient — the next poll tick will try again
      }
    }, 1500);
    return () => clearTimeout(timer);
  }, [status]);

  function pickSaved(r: SavedRecipient) {
    setRecipientUserId(r.recipientUserId);
    setRecipientLabel(r.label);
  }

  async function onSave() {
    if (!recipientUserId.trim()) return;
    const label = recipientLabel.trim() || recipientUserId.slice(0, 8);
    const result = await saveRecipient(label, recipientUserId.trim());
    if (result.kind === "created") {
      setSaved(result.recipients);
      setSaveNotice({ kind: "ok", text: `Saved as "${label}".` });
    } else if (result.kind === "updated") {
      setSaved(result.recipients);
      setSaveNotice({ kind: "ok", text: `Already saved — label updated to "${label}".` });
    } else {
      setSaveNotice({
        kind: "warn",
        text: `"${label}" is already used for a different recipient — pick another label.`,
      });
    }
    setTimeout(() => setSaveNotice(null), 4000);
  }

  async function onRemove(id: string) {
    setSaved(await removeRecipient(id));
  }

  const fromAccount = accounts.find((a) => a.currency === fromCurrency);
  const fromBalanceZero = fromAccount != null && Number(fromAccount.available) <= 0;

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setStatus(null);
    if (fromBalanceZero) {
      setError(`Your ${fromCurrency} account has no available balance.`);
      return;
    }
    setSubmitting(true);
    try {
      const idempotencyKey = crypto.randomUUID();
      const accepted = await api.post<TransferAccepted>(
        "/v1/transfers",
        { recipientUserId, fromCurrency, toCurrency, amount },
        { "Idempotency-Key": idempotencyKey },
      );
      setStatus({ sagaId: accepted.sagaId, status: accepted.status, failureReason: null });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Transfer failed");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto flex max-w-md flex-col gap-6">
      <div className="animate-fade-up text-center">
        <div
          className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-2xl"
          style={{ background: "var(--emerald-soft)", color: "var(--emerald)" }}
        >
          <TransferIcon className="h-6 w-6" />
        </div>
        <h1 className="text-2xl font-semibold tracking-tight">Transfer</h1>
        <p className="mt-1 text-sm" style={{ color: "var(--text-faint)" }}>Send money to another Lynx user.</p>
      </div>

      {saved.length > 0 && !status && (
        <div className="animate-fade-up flex flex-wrap gap-2">
          {saved.map((r) => (
            <div
              key={r.id}
              className="group flex items-center gap-1.5 rounded-full py-1 pl-3 pr-1.5 text-xs font-medium transition-colors"
              style={{
                background: recipientUserId === r.recipientUserId ? "var(--emerald-soft)" : "var(--surface)",
                border: `1px solid ${recipientUserId === r.recipientUserId ? "var(--emerald)" : "var(--border)"}`,
                color: recipientUserId === r.recipientUserId ? "var(--emerald)" : "var(--text-soft)",
              }}
            >
              <button type="button" onClick={() => pickSaved(r)} className="flex items-center gap-1.5">
                <StarIcon className="h-3 w-3" filled />
                {r.label}
              </button>
              <button
                type="button"
                onClick={() => onRemove(r.id)}
                className="rounded-full p-1 opacity-0 transition-opacity group-hover:opacity-100"
                style={{ color: "var(--rose)" }}
                title="Remove"
              >
                <TrashIcon className="h-3 w-3" />
              </button>
            </div>
          ))}
        </div>
      )}

      {status ? (
        <TransferStatusCard status={status} onReset={() => setStatus(null)} />
      ) : (
        <form onSubmit={onSubmit} className="glass animate-fade-up flex flex-col gap-4 p-6" style={{ animationDelay: "80ms" }}>
          <label className="flex flex-col gap-1.5 text-sm" style={{ color: "var(--text-soft)" }}>
            Recipient user ID
            <input
              value={recipientUserId}
              onChange={(e) => setRecipientUserId(e.target.value)}
              required
              placeholder="user-id or select a saved one above"
              className="rounded-lg px-3.5 py-2.5 font-mono text-xs outline-none"
              style={{ background: "var(--surface)", border: "1px solid var(--border)", color: "var(--foreground)" }}
            />
          </label>
          <div className="flex items-end gap-2">
            <label className="flex flex-1 flex-col gap-1.5 text-sm" style={{ color: "var(--text-soft)" }}>
              Save as
              <input
                value={recipientLabel}
                onChange={(e) => setRecipientLabel(e.target.value)}
                placeholder="e.g. Bob"
                className="rounded-lg px-3.5 py-2 text-sm outline-none"
                style={{ background: "var(--surface)", border: "1px solid var(--border)", color: "var(--foreground)" }}
              />
            </label>
            <button
              type="button"
              onClick={onSave}
              disabled={!recipientUserId.trim()}
              title="Save this recipient"
              className="flex h-[38px] w-[38px] shrink-0 items-center justify-center rounded-lg transition-colors disabled:opacity-40"
              style={{ background: "var(--surface)", border: "1px solid var(--border)", color: "var(--brass)" }}
            >
              <PlusIcon className="h-4 w-4" />
            </button>
          </div>
          {saveNotice && (
            <p
              className="animate-fade-in -mt-2 text-xs"
              style={{ color: saveNotice.kind === "warn" ? "var(--rose)" : "var(--emerald)" }}
            >
              {saveNotice.kind === "warn" ? "⚠ " : "✓ "}
              {saveNotice.text}
            </p>
          )}

          <label className="flex flex-col gap-1.5 text-sm" style={{ color: "var(--text-soft)" }}>
            From currency (your account)
            <select
              value={fromCurrency}
              onChange={(e) => setFromCurrency(e.target.value)}
              className="rounded-lg px-3.5 py-2.5 text-sm outline-none"
              style={{ background: "var(--surface)", border: "1px solid var(--border)", color: "var(--foreground)" }}
            >
              {accounts.map((a) => (
                <option key={a.id} value={a.currency}>
                  {a.currency} — available {a.available}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1.5 text-sm" style={{ color: "var(--text-soft)" }}>
            To currency (recipient&apos;s account)
            <select
              value={toCurrency}
              onChange={(e) => setToCurrency(e.target.value)}
              required
              className="rounded-lg px-3.5 py-2.5 text-sm outline-none"
              style={{ background: "var(--surface)", border: "1px solid var(--border)", color: "var(--foreground)" }}
            >
              {currencies.map((c) => (
                <option key={c} value={c}>{c}</option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1.5 text-sm" style={{ color: "var(--text-soft)" }}>
            Amount
            <input
              type="number"
              step="0.01"
              min="0.01"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              required
              placeholder="0.00"
              className="rounded-lg px-3.5 py-2.5 text-lg font-semibold outline-none tabular"
              style={{ background: "var(--surface)", border: "1px solid var(--border)", color: "var(--foreground)" }}
            />
          </label>
          <ErrorText message={error} />
          <SubmitButton disabled={submitting || fromBalanceZero}>
            {submitting ? "Sending…" : fromBalanceZero ? "No available balance" : "Send transfer"}
          </SubmitButton>
        </form>
      )}
    </div>
  );
}

const STEPS = ["Reserving funds", "Processing", "Complete"] as const;

/** Raw backend saga statuses, translated into words a real user would say — not the ledger's own phase names. */
const STATUS_LABELS: Record<string, string> = {
  HOLDING: "In progress",
  SETTLED: "Complete",
  FAILED: "Failed",
  RELEASED: "Reversed",
};

function friendlyStatus(value: string) {
  return STATUS_LABELS[value] ?? value;
}

function TransferStatusCard({ status, onReset }: { status: TransferStatus; onReset: () => void }) {
  const failed = status.status === "FAILED" || status.status === "RELEASED";
  const settled = status.status === "SETTLED";
  const stepIndex = failed ? 1 : settled ? 2 : 0;

  return (
    <div className={`glass animate-scale-in flex flex-col gap-6 p-7 ${failed ? "animate-shake" : ""}`}>
      <div className="flex items-center justify-between text-xs" style={{ color: "var(--text-faint)" }}>
        <span>
          Saga <span className="font-mono">{status.sagaId.slice(0, 8)}…</span>
        </span>
        <StatusPill value={status.status} />
      </div>

      <div className="flex items-center">
        {STEPS.map((label, i) => {
          const isDone = i < stepIndex || settled;
          const isActive = i === stepIndex && !settled && !failed;
          const isFailedHere = failed && i === 1;
          return (
            <div key={label} className="flex flex-1 items-center last:flex-none">
              <div className="flex flex-col items-center gap-1.5">
                <div
                  className={`flex h-8 w-8 items-center justify-center rounded-full text-xs font-semibold transition-colors ${isActive ? "pulse-dot" : ""}`}
                  style={{
                    background: isFailedHere
                      ? "var(--rose-soft)"
                      : isDone
                        ? "var(--emerald-soft)"
                        : isActive
                          ? "var(--amber-soft)"
                          : "var(--surface)",
                    color: isFailedHere ? "var(--rose)" : isDone ? "var(--emerald)" : isActive ? "var(--amber)" : "var(--text-faint)",
                    border: `1px solid ${isDone ? "var(--emerald)" : isActive ? "var(--amber)" : isFailedHere ? "var(--rose)" : "var(--border)"}`,
                  }}
                >
                  {isFailedHere ? "✕" : isDone ? "✓" : i + 1}
                </div>
                <span className="text-center text-[11px]" style={{ color: "var(--text-faint)" }}>
                  {isFailedHere ? "Failed" : label}
                </span>
              </div>
              {i < STEPS.length - 1 && (
                <div className="mx-1 h-0.5 flex-1 overflow-hidden rounded-full" style={{ background: "var(--border)" }}>
                  <div
                    className="animate-progress h-full rounded-full"
                    style={{
                      width: i < stepIndex || settled ? "100%" : "0%",
                      background: failed ? "var(--rose)" : "var(--emerald)",
                    }}
                  />
                </div>
              )}
            </div>
          );
        })}
      </div>

      <div className="flex flex-col items-center gap-2 py-2 text-center">
        {settled && <AnimatedCheck className="h-16 w-16" />}
        {failed && (
          <div
            className="flex h-16 w-16 items-center justify-center rounded-full text-2xl"
            style={{ background: "var(--rose-soft)", color: "var(--rose)" }}
          >
            ✕
          </div>
        )}
        {!settled && !failed && (
          <div className="flex h-16 w-16 items-center justify-center">
            <span className="pulse-dot h-3 w-3 rounded-full" style={{ background: "var(--amber)", color: "var(--amber)" }} />
          </div>
        )}
        <p className="mt-1 font-medium">
          {settled && "Money sent successfully"}
          {failed && "Transfer didn't go through"}
          {!settled && !failed && "Sending your money…"}
        </p>
        {status.failureReason && (
          <p className="text-sm" style={{ color: "var(--rose)" }}>{status.failureReason}</p>
        )}
      </div>

      {(settled || failed) && (
        <button
          onClick={onReset}
          className="rounded-lg px-4 py-2.5 text-sm font-medium transition-colors"
          style={{ background: "var(--surface)", border: "1px solid var(--border)", color: "var(--foreground)" }}
        >
          Send another transfer
        </button>
      )}
    </div>
  );
}

function StatusPill({ value }: { value: string }) {
  const color =
    value === "SETTLED" ? "var(--emerald)" : value === "FAILED" || value === "RELEASED" ? "var(--rose)" : "var(--amber)";
  const bg =
    value === "SETTLED" ? "var(--emerald-soft)" : value === "FAILED" || value === "RELEASED" ? "var(--rose-soft)" : "var(--amber-soft)";
  return (
    <span className="rounded-full px-2.5 py-1 font-semibold" style={{ color, background: bg }}>
      {friendlyStatus(value)}
    </span>
  );
}

export default function TransferPage() {
  return (
    <ProtectedRoute>
      <TransferContent />
    </ProtectedRoute>
  );
}
