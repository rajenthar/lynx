"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import { useAuth } from "@/lib/auth-context";
import { api, ApiError } from "@/lib/api";
import type { Account } from "@/lib/types";
import { CurrencyBadge, DepositIcon, TransferIcon, HistoryIcon, WalletIcon } from "@/components/Icons";

function DashboardContent() {
  const { profile } = useAuth();
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .get<Account[]>("/v1/accounts")
      .then(setAccounts)
      .catch((err) => setError(err instanceof ApiError ? err.message : "Could not load accounts"));
  }, []);

  const totalHeld = accounts?.reduce((sum, a) => sum + Number(a.held), 0) ?? 0;

  return (
    <div className="flex flex-col gap-8">
      <div className="animate-fade-up">
        <h1 className="text-2xl font-semibold tracking-tight">
          Welcome back{profile ? <span className="text-gradient">, {profile.name}</span> : ""}
        </h1>
        <p className="mt-1" style={{ color: "var(--text-faint)" }}>Here&apos;s a summary of your Lynx accounts.</p>
      </div>

      {error && <ErrorBanner message={error} />}

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <SummaryTile index={0} label="Currencies held" value={accounts ? String(accounts.length) : null} />
        <SummaryTile
          index={1}
          label="Total available"
          value={
            accounts
              ? accounts.map((a) => `${a.available} ${a.currency}`).join(" · ") || "—"
              : null
          }
        />
        <SummaryTile index={2} label="Total on hold" value={accounts ? totalHeld.toFixed(2) : null} />
      </div>

      <div className="glass animate-fade-up p-6" style={{ animationDelay: "160ms" }}>
        <h2 className="mb-4 text-lg font-medium">Your balances</h2>
        {accounts === null && !error && <BalancesSkeleton />}
        {accounts && accounts.length === 0 && (
          <div className="flex flex-col items-center gap-3 py-8 text-center">
            <WalletIcon className="h-10 w-10" />
            <p style={{ color: "var(--text-faint)" }}>
              You don&apos;t have any currency accounts yet.{" "}
              <Link href="/accounts" className="font-medium" style={{ color: "var(--emerald)" }}>
                Create one
              </Link>
              .
            </p>
          </div>
        )}
        {accounts && accounts.length > 0 && (
          <div className="flex flex-col gap-2">
            {accounts.map((a, i) => (
              <div
                key={a.id}
                className="stagger animate-fade-up flex items-center gap-4 rounded-xl p-3 transition-colors"
                style={{ ["--stagger-index" as string]: i, background: "var(--surface)" }}
              >
                <CurrencyBadge currency={a.currency} />
                <div className="flex-1">
                  <p className="font-medium">{a.currency}</p>
                  <p className="text-xs" style={{ color: "var(--text-faint)" }}>Available balance</p>
                </div>
                <div className="text-right">
                  <p className="tabular text-lg font-semibold" style={{ color: "var(--emerald)" }}>{a.available}</p>
                  {Number(a.held) > 0 && (
                    <p className="tabular text-xs" style={{ color: "var(--amber)" }}>{a.held} held</p>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="flex flex-wrap gap-3">
        <QuickLink href="/accounts" label="Manage accounts" Icon={WalletIcon} />
        <QuickLink href="/deposit" label="Deposit" Icon={DepositIcon} />
        <QuickLink href="/transfer" label="Transfer" Icon={TransferIcon} />
        <QuickLink href="/history" label="View history" Icon={HistoryIcon} />
      </div>
    </div>
  );
}

function SummaryTile({ label, value, index }: { label: string; value: string | null; index: number }) {
  return (
    <div
      className="glass glass-hover stagger animate-fade-up p-4"
      style={{ ["--stagger-index" as string]: index }}
    >
      <p className="text-xs uppercase tracking-wide" style={{ color: "var(--text-faint)" }}>{label}</p>
      {value === null ? (
        <div className="skeleton mt-2 h-6 w-24" />
      ) : (
        <p className="tabular mt-1 text-lg font-medium">{value}</p>
      )}
    </div>
  );
}

function BalancesSkeleton() {
  return (
    <div className="flex flex-col gap-2">
      {[0, 1].map((i) => (
        <div key={i} className="flex items-center gap-4 rounded-xl p-3" style={{ background: "var(--surface)" }}>
          <div className="skeleton h-10 w-10 rounded-full" />
          <div className="flex-1">
            <div className="skeleton mb-1.5 h-4 w-16" />
            <div className="skeleton h-3 w-24" />
          </div>
          <div className="skeleton h-5 w-16" />
        </div>
      ))}
    </div>
  );
}

function ErrorBanner({ message }: { message: string }) {
  return (
    <div
      className="animate-fade-in rounded-xl px-4 py-3 text-sm"
      style={{ background: "var(--rose-soft)", color: "var(--rose)", border: "1px solid var(--rose-soft)" }}
    >
      {message}
    </div>
  );
}

function QuickLink({ href, label, Icon }: { href: string; label: string; Icon: (p: { className?: string }) => React.ReactElement }) {
  return (
    <Link
      href={href}
      className="glass glass-hover flex items-center gap-2 px-4 py-2.5 text-sm font-medium"
      style={{ color: "var(--foreground)" }}
    >
      <Icon className="h-4 w-4" />
      {label}
    </Link>
  );
}

export default function DashboardPage() {
  return (
    <ProtectedRoute>
      <DashboardContent />
    </ProtectedRoute>
  );
}
