"use client";

import { useEffect, useState } from "react";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import { api, ApiError } from "@/lib/api";
import type { Account, DepositAccepted } from "@/lib/types";
import { ErrorText, SubmitButton } from "@/components/AuthCard";
import { AnimatedCheck, CurrencyBadge, DepositIcon } from "@/components/Icons";

function DepositContent() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [accountId, setAccountId] = useState("");
  const [amount, setAmount] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<{ depositId: string; amount: string; currency: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);

  function loadAccounts() {
    return api.get<Account[]>("/v1/accounts").then((accs) => {
      setAccounts(accs);
      return accs;
    });
  }

  useEffect(() => {
    loadAccounts().then((accs) => {
      if (accs.length > 0) setAccountId(accs[0].id);
    });
  }, []);

  // The deposit itself only accepts synchronously — the actual balance
  // updates asynchronously once the ledger event comes back through
  // account-service's Kafka projection (see EventProjector), so a single
  // refetch right after "success" often still shows the PRE-deposit
  // balance. Poll a few times (same setTimeout-self-reschedule pattern
  // transfer/page.tsx uses for its own status polling) until the
  // balance this deposit was for actually changes, or give up quietly.
  useEffect(() => {
    if (!success) return;
    const depositCurrency = success.currency;
    let cancelled = false;
    let attempts = 0;
    const before = accounts.find((a) => a.currency === depositCurrency)?.available;

    function poll() {
      if (cancelled || attempts >= 6) return;
      attempts += 1;
      loadAccounts().then((accs) => {
        if (cancelled) return;
        const after = accs.find((a) => a.currency === depositCurrency)?.available;
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
  }, [success]);

  const selected = accounts.find((a) => a.id === accountId);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    if (!selected) return;
    setSubmitting(true);
    try {
      const result = await api.post<DepositAccepted>(`/v1/accounts/${accountId}/deposit`, {
        amount,
        currencyCode: selected.currency,
      });
      setSuccess({ depositId: result.depositId, amount, currency: selected.currency });
      setAmount("");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Deposit failed");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto flex max-w-sm flex-col gap-6">
      <div className="animate-fade-up text-center">
        <div
          className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-2xl"
          style={{ background: "var(--emerald-soft)", color: "var(--emerald)" }}
        >
          <DepositIcon className="h-6 w-6" />
        </div>
        <h1 className="text-2xl font-semibold tracking-tight">Deposit</h1>
        <p className="mt-1 text-sm" style={{ color: "var(--text-faint)" }}>Add funds to one of your accounts.</p>
      </div>

      {success ? (
        <SuccessCard
          amount={success.amount}
          currency={success.currency}
          depositId={success.depositId}
          onDone={() => setSuccess(null)}
        />
      ) : (
        <form onSubmit={onSubmit} className="glass animate-fade-up flex flex-col gap-4 p-6">
          <label className="flex flex-col gap-1.5 text-sm" style={{ color: "var(--text-soft)" }}>
            Account
            <div className="flex flex-col gap-2">
              {accounts.length === 0 && (
                <p className="text-sm" style={{ color: "var(--text-faint)" }}>No accounts yet</p>
              )}
              {accounts.map((a) => (
                <button
                  type="button"
                  key={a.id}
                  onClick={() => setAccountId(a.id)}
                  className="flex items-center gap-3 rounded-lg p-2.5 text-left transition-colors"
                  style={{
                    background: accountId === a.id ? "var(--emerald-soft)" : "var(--surface)",
                    border: `1px solid ${accountId === a.id ? "var(--emerald)" : "var(--border)"}`,
                  }}
                >
                  <CurrencyBadge currency={a.currency} size={32} />
                  <span className="flex-1 text-sm font-medium" style={{ color: "var(--foreground)" }}>
                    {a.currency}
                  </span>
                  <span className="tabular text-sm" style={{ color: "var(--text-faint)" }}>{a.available}</span>
                </button>
              ))}
            </div>
          </label>
          <label className="flex flex-col gap-1.5 text-sm" style={{ color: "var(--text-soft)" }}>
            Amount {selected ? `(${selected.currency})` : ""}
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
              onFocus={(e) => (e.currentTarget.style.borderColor = "var(--emerald)")}
              onBlur={(e) => (e.currentTarget.style.borderColor = "var(--border)")}
            />
          </label>
          <ErrorText message={error} />
          <SubmitButton disabled={submitting || !selected}>
            {submitting ? "Depositing…" : "Deposit"}
          </SubmitButton>
        </form>
      )}
    </div>
  );
}

function SuccessCard({
  amount,
  currency,
  depositId,
  onDone,
}: {
  amount: string;
  currency: string;
  depositId: string;
  onDone: () => void;
}) {
  return (
    <div className="glass animate-scale-in flex flex-col items-center gap-4 p-8 text-center">
      <div className="relative">
        <AnimatedCheck className="h-20 w-20" />
        <div
          className="animate-coin-drop absolute -right-2 -top-2 flex h-9 w-9 items-center justify-center rounded-full text-xs font-bold text-[#04140d] shadow-lg"
          style={{ background: "linear-gradient(135deg, var(--brass), #f3d98e)", animationDelay: "0.5s", animationFillMode: "backwards" }}
        >
          <span className="animate-coin-shine">$</span>
        </div>
      </div>
      <div>
        <p className="animate-fade-up text-2xl font-semibold tabular" style={{ animationDelay: "0.65s", color: "var(--emerald)" }}>
          +{amount} {currency}
        </p>
        <p className="animate-fade-up mt-1 text-sm" style={{ animationDelay: "0.75s", color: "var(--text-faint)" }}>
          Deposit accepted — your balance updates in a moment.
        </p>
        <p className="animate-fade-up mt-2 font-mono text-xs" style={{ animationDelay: "0.85s", color: "var(--text-faint)" }}>
          {depositId.slice(0, 8)}…
        </p>
      </div>
      <button
        onClick={onDone}
        className="animate-fade-up rounded-lg px-4 py-2 text-sm font-medium transition-colors"
        style={{ animationDelay: "0.95s", background: "var(--surface)", color: "var(--foreground)", border: "1px solid var(--border)" }}
      >
        Make another deposit
      </button>
    </div>
  );
}

export default function DepositPage() {
  return (
    <ProtectedRoute>
      <DepositContent />
    </ProtectedRoute>
  );
}
