"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { ApiError } from "@/lib/api";
import { AuthCard, ErrorText, FormField, SubmitButton } from "@/components/AuthCard";

export default function LoginPage() {
  const { login } = useAuth();
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email, password);
      router.push("/dashboard");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Login failed");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthCard title="Welcome back" subtitle="Log in to your Lynx account">
      <form onSubmit={onSubmit} className="flex flex-col gap-4">
        <FormField
          label="Email"
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />
        <FormField
          label="Password"
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
        <ErrorText message={error} />
        <SubmitButton disabled={submitting}>
          {submitting ? "Logging in…" : "Log in"}
        </SubmitButton>
      </form>
      <p className="text-sm" style={{ color: "var(--text-faint)" }}>
        Need an account?{" "}
        <Link href="/register" className="font-medium" style={{ color: "var(--emerald)" }}>
          Register
        </Link>
      </p>
    </AuthCard>
  );
}
