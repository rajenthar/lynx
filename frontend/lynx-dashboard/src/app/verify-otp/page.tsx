"use client";

import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { ApiError } from "@/lib/api";
import { AuthCard, ErrorText, FormField, SubmitButton } from "@/components/AuthCard";

function VerifyOtpForm() {
  const { verifyOtp, resendOtp } = useAuth();
  const router = useRouter();
  const searchParams = useSearchParams();
  const [email, setEmail] = useState(searchParams.get("email") ?? "");
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await verifyOtp(email, code);
      router.push("/dashboard");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Verification failed");
    } finally {
      setSubmitting(false);
    }
  }

  async function onResend() {
    setError(null);
    setInfo(null);
    try {
      await resendOtp(email);
      setInfo("A new code was sent.");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not resend code");
    }
  }

  return (
    <AuthCard title="Verify your email" subtitle="Enter the 6-digit code we emailed you">
      <p className="-mt-2 text-xs" style={{ color: "var(--text-faint)" }}>
        Don&apos;t see it in your inbox? Check your spam/junk folder — it can take a minute to arrive.
      </p>
      <form onSubmit={onSubmit} className="flex flex-col gap-4">
        <FormField
          label="Email"
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />
        <label className="flex flex-col gap-1.5 text-sm" style={{ color: "var(--text-soft)" }}>
          Verification code
          <input
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
            maxLength={6}
            pattern="\d{6}"
            inputMode="numeric"
            required
            className="rounded-lg px-3.5 py-3 text-center text-2xl font-semibold outline-none transition-colors tabular"
            style={{
              background: "var(--surface)",
              border: "1px solid var(--border)",
              color: "var(--foreground)",
              letterSpacing: "0.5em",
              textIndent: "0.5em",
            }}
            onFocus={(e) => (e.currentTarget.style.borderColor = "var(--emerald)")}
            onBlur={(e) => (e.currentTarget.style.borderColor = "var(--border)")}
          />
        </label>
        <ErrorText message={error} />
        {info && (
          <p className="animate-fade-in text-sm" style={{ color: "var(--emerald)" }}>
            ✓ {info}
          </p>
        )}
        <SubmitButton disabled={submitting}>
          {submitting ? "Verifying…" : "Verify"}
        </SubmitButton>
      </form>
      <button
        onClick={onResend}
        className="text-sm transition-colors"
        style={{ color: "var(--text-faint)" }}
        onMouseEnter={(e) => (e.currentTarget.style.color = "var(--text-soft)")}
        onMouseLeave={(e) => (e.currentTarget.style.color = "var(--text-faint)")}
      >
        Resend code
      </button>
    </AuthCard>
  );
}

export default function VerifyOtpPage() {
  return (
    <Suspense fallback={null}>
      <VerifyOtpForm />
    </Suspense>
  );
}
