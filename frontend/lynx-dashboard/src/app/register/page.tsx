"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { ApiError } from "@/lib/api";
import { AuthCard, ErrorText, FormField, PasswordRuleChecklist, SubmitButton } from "@/components/AuthCard";
import { isPasswordValid, PASSWORD_RULES } from "@/lib/password";

export default function RegisterPage() {
  const { register } = useAuth();
  const router = useRouter();
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (!isPasswordValid(password)) {
      setError("Password doesn't meet all the requirements below");
      return;
    }
    setSubmitting(true);
    try {
      await register(email, password, name);
      router.push(`/verify-otp?email=${encodeURIComponent(email)}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Registration failed");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthCard title="Create your account" subtitle="A few details, then we'll email you a code">
      <form onSubmit={onSubmit} className="flex flex-col gap-4">
        <FormField label="Name" value={name} onChange={(e) => setName(e.target.value)} required />
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
          minLength={8}
          required
        />
        {password.length > 0 && <PasswordRuleChecklist password={password} rules={PASSWORD_RULES} />}
        <ErrorText message={error} />
        <SubmitButton disabled={submitting}>
          {submitting ? "Sending code…" : "Register"}
        </SubmitButton>
      </form>
      <p className="text-sm" style={{ color: "var(--text-faint)" }}>
        Already have an account?{" "}
        <Link href="/login" className="font-medium" style={{ color: "var(--emerald)" }}>
          Log in
        </Link>
      </p>
    </AuthCard>
  );
}
