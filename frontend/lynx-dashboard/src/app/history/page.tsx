"use client";

import { useEffect, useState } from "react";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import { api, ApiError } from "@/lib/api";
import type { Account, HistoryEntry } from "@/lib/types";
import { CurrencyBadge, HistoryIcon } from "@/components/Icons";

function HistoryContent() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [accountId, setAccountId] = useState("");
  const [entries, setEntries] = useState<HistoryEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.get<Account[]>("/v1/accounts").then((accs) => {
      setAccounts(accs);
      if (accs.length > 0) setAccountId(accs[0].id);
    });
  }, []);

  useEffect(() => {
    if (!accountId) return;
    let cancelled = false;
    async function run() {
      setEntries(null);
      setError(null);
      try {
        const result = await api.get<HistoryEntry[]>(`/v1/accounts/${accountId}/history?limit=50`);
        if (!cancelled) setEntries(result);
      } catch (err) {
        if (!cancelled) setError(err instanceof ApiError ? err.message : "Could not load history");
      }
    }
    run();
    return () => {
      cancelled = true;
    };
  }, [accountId]);

  const selectedAccount = accounts.find((a) => a.id === accountId);

  return (
    <div className="flex flex-col gap-6">
      <div className="animate-fade-up">
        <h1 className="text-2xl font-semibold tracking-tight">History</h1>
        <p className="mt-1" style={{ color: "var(--text-faint)" }}>Every ledger entry for the selected account.</p>
      </div>

      <div className="animate-fade-up flex max-w-xs flex-wrap gap-2">
        {accounts.map((a) => (
          <button
            key={a.id}
            onClick={() => setAccountId(a.id)}
            className="flex items-center gap-2 rounded-full py-1.5 pl-2 pr-3.5 text-sm font-medium transition-colors"
            style={{
              background: accountId === a.id ? "var(--emerald-soft)" : "var(--surface)",
              border: `1px solid ${accountId === a.id ? "var(--emerald)" : "var(--border)"}`,
              color: accountId === a.id ? "var(--emerald)" : "var(--text-soft)",
            }}
          >
            <CurrencyBadge currency={a.currency} size={22} />
            {a.currency}
          </button>
        ))}
      </div>

      {error && (
        <p className="rounded-xl px-4 py-3 text-sm" style={{ background: "var(--rose-soft)", color: "var(--rose)" }}>
          {error}
        </p>
      )}

      <div className="glass animate-fade-up p-6" style={{ animationDelay: "80ms" }}>
        {entries === null && !error && (
          <div className="flex flex-col gap-2">
            {[0, 1, 2].map((i) => (
              <div key={i} className="skeleton h-12 w-full" />
            ))}
          </div>
        )}
        {entries && entries.length === 0 && (
          <div className="flex flex-col items-center gap-2 py-8 text-center" style={{ color: "var(--text-faint)" }}>
            <HistoryIcon className="h-10 w-10" />
            <p>No entries yet for {selectedAccount?.currency ?? "this account"}.</p>
          </div>
        )}
        {entries && entries.length > 0 && (
          <div className="flex flex-col gap-1.5">
            {entries.map((entry, i) => (
              <div
                key={i}
                className="stagger animate-fade-up flex items-center gap-3 rounded-xl p-3"
                style={{ ["--stagger-index" as string]: i, background: "var(--surface)" }}
              >
                <EntryTypeIcon entryType={entry.entryType} />
                <div className="flex-1">
                  <p className="text-sm font-medium">{formatEntryType(entry.entryType)}</p>
                  <p className="font-mono text-xs" style={{ color: "var(--text-faint)" }}>
                    {entry.sagaId.slice(0, 8)}… · {new Date(entry.createdAt).toLocaleString()}
                  </p>
                </div>
                <p
                  className="tabular font-semibold"
                  style={{ color: isCredit(entry.entryType) ? "var(--emerald)" : "var(--rose)" }}
                >
                  {isCredit(entry.entryType) ? "+" : "−"}{entry.amount} {entry.currency}
                </p>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function isCredit(entryType: string) {
  return entryType.toUpperCase().endsWith("_CR");
}

/**
 * Every raw {@code EntryType} the ledger writes, translated into a label an
 * end user actually understands — the DR/CR suffix (which saga PHASE this
 * leg belongs to, not a synonym for "debit") is meaningless outside the
 * backend; the +/- sign and color already carry that distinction visually,
 * so the label itself only needs to say WHAT kind of money movement this
 * was.
 */
const ENTRY_TYPE_LABELS: Record<string, string> = {
  DEPOSIT_DR: "Deposit",
  DEPOSIT_CR: "Deposit",
  HOLD_DR: "Transfer sent",
  HOLD_CR: "Transfer received (pending)",
  LOCK_DR: "Currency exchange",
  LOCK_CR: "Currency exchange",
  SETTLE_DR: "Transfer sent",
  SETTLE_CR: "Transfer received",
  RELEASE_DR: "Transfer reversed",
  RELEASE_CR: "Transfer reversed",
};

function formatEntryType(entryType: string) {
  return ENTRY_TYPE_LABELS[entryType.toUpperCase()] ?? entryType;
}

function EntryTypeIcon({ entryType }: { entryType: string }) {
  const credit = isCredit(entryType);
  return (
    <div
      className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-sm font-bold"
      style={{
        background: credit ? "var(--emerald-soft)" : "var(--rose-soft)",
        color: credit ? "var(--emerald)" : "var(--rose)",
      }}
    >
      {credit ? "↓" : "↑"}
    </div>
  );
}

export default function HistoryPage() {
  return (
    <ProtectedRoute>
      <HistoryContent />
    </ProtectedRoute>
  );
}
