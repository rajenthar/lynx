"use client";

import { useEffect, useState } from "react";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import { api, ApiError } from "@/lib/api";
import type { Account } from "@/lib/types";
import { ErrorText, SubmitButton } from "@/components/AuthCard";
import { CurrencyBadge, PlusIcon, WalletIcon } from "@/components/Icons";

function AccountsContent() {
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [currencies, setCurrencies] = useState<string[]>([]);
  // SGD, not blank — matches the account every new user already gets by
  // default (auth-service seeds one right after OTP verification), so the
  // dropdown's default selection isn't just "whatever loads first" but
  // deliberately the same starter currency, before the real list even
  // arrives from the backend.
  const [currency, setCurrency] = useState("SGD");
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [justCreated, setJustCreated] = useState<string | null>(null);

  function load() {
    api
      .get<Account[]>("/v1/accounts")
      .then(setAccounts)
      .catch((err) => setError(err instanceof ApiError ? err.message : "Could not load accounts"));
  }

  useEffect(load, []);
  useEffect(() => {
    api.get<string[]>("/v1/accounts/currencies").then((list) => {
      setCurrencies(list);
      setCurrency((current) => current || list[0] || "");
    });
  }, []);

  const takenCurrencies = new Set((accounts ?? []).map((a) => a.currency));

  async function onCreate(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setCreating(true);
    try {
      const created = await api.post<Account>("/v1/accounts", { currencyCode: currency });
      load();
      setJustCreated(created.id);
      setTimeout(() => setJustCreated(null), 1600);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not create account");
    } finally {
      setCreating(false);
    }
  }

  return (
    <div className="flex flex-col gap-8">
      <div className="animate-fade-up">
        <h1 className="text-2xl font-semibold tracking-tight">Your accounts</h1>
        <p className="mt-1" style={{ color: "var(--text-faint)" }}>One account per currency — hold as many as you like.</p>
      </div>

      <div className="glass animate-fade-up p-6">
        <h2 className="mb-4 text-lg font-medium">Balances</h2>
        {accounts === null && <div className="skeleton h-20 w-full" />}
        {accounts && accounts.length === 0 && (
          <div className="flex flex-col items-center gap-2 py-8 text-center" style={{ color: "var(--text-faint)" }}>
            <WalletIcon className="h-10 w-10" />
            <p>No accounts yet — create one below.</p>
          </div>
        )}
        {accounts && accounts.length > 0 && (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {accounts.map((a, i) => (
              <div
                key={a.id}
                className={`stagger animate-fade-up flex items-center gap-3 rounded-xl p-4 transition-shadow ${
                  justCreated === a.id ? "animate-scale-in" : ""
                }`}
                style={{
                  ["--stagger-index" as string]: i,
                  background: "var(--surface)",
                  boxShadow: justCreated === a.id ? "0 0 0 2px var(--emerald)" : "none",
                }}
              >
                <CurrencyBadge currency={a.currency} size={44} />
                <div className="flex-1">
                  <p className="font-medium">{a.currency}</p>
                  <p className="text-xs" style={{ color: "var(--text-faint)" }}>
                    Updated {new Date(a.updatedAt).toLocaleDateString()}
                  </p>
                </div>
                <div className="text-right">
                  <p className="tabular font-semibold" style={{ color: "var(--emerald)" }}>{a.available}</p>
                  <p className="tabular text-xs" style={{ color: "var(--text-faint)" }}>{a.held} held</p>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="glass animate-fade-up max-w-sm p-6" style={{ animationDelay: "100ms" }}>
        <h2 className="mb-4 flex items-center gap-2 text-lg font-medium">
          <PlusIcon className="h-4 w-4" style={{ color: "var(--emerald)" }} />
          Open a new currency account
        </h2>
        <form onSubmit={onCreate} className="flex flex-col gap-4">
          <label className="flex flex-col gap-1.5 text-sm" style={{ color: "var(--text-soft)" }}>
            Currency
            <select
              value={currency}
              onChange={(e) => setCurrency(e.target.value)}
              className="rounded-lg px-3.5 py-2.5 text-sm outline-none"
              style={{ background: "var(--surface)", border: "1px solid var(--border)", color: "var(--foreground)" }}
            >
              {currencies.map((c) => (
                <option key={c} value={c} disabled={takenCurrencies.has(c)}>
                  {c}
                  {takenCurrencies.has(c) ? " (already open)" : ""}
                </option>
              ))}
            </select>
          </label>
          <ErrorText message={error} />
          <SubmitButton disabled={creating}>
            {creating ? "Creating…" : "Create account"}
          </SubmitButton>
        </form>
      </div>
    </div>
  );
}

export default function AccountsPage() {
  return (
    <ProtectedRoute>
      <AccountsContent />
    </ProtectedRoute>
  );
}
