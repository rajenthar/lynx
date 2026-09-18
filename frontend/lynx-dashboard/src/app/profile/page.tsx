"use client";

import { useState } from "react";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import { useAuth } from "@/lib/auth-context";
import { ApiError } from "@/lib/api";
import { CheckIcon, InitialsAvatar } from "@/components/Icons";
import { ErrorText, FormField, PasswordRuleChecklist, SubmitButton } from "@/components/AuthCard";
import { isPasswordValid, PASSWORD_RULES } from "@/lib/password";

function ProfileContent() {
  const { profile, changeDetails } = useAuth();
  const [editing, setEditing] = useState(false);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newName, setNewName] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (!profile) return null;

  function startEditing() {
    setNewName(profile!.name);
    setNewPassword("");
    setCurrentPassword("");
    setError(null);
    setSuccess(null);
    setEditing(true);
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSuccess(null);

    const trimmedName = newName.trim();
    const nameChanged = trimmedName.length > 0 && trimmedName !== profile!.name;
    const passwordChanged = newPassword.length > 0;

    if (!nameChanged && !passwordChanged) {
      setError("Change your name and/or password first");
      return;
    }
    if (passwordChanged && !isPasswordValid(newPassword)) {
      setError("New password doesn't meet all the requirements below");
      return;
    }
    if (!currentPassword) {
      setError("Enter your current password to confirm this change");
      return;
    }

    setSubmitting(true);
    try {
      await changeDetails(
        currentPassword,
        nameChanged ? trimmedName : undefined,
        passwordChanged ? newPassword : undefined,
      );
      setSuccess("Updated.");
      setEditing(false);
      setCurrentPassword("");
      setNewPassword("");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Update failed");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto flex max-w-sm flex-col gap-6">
      <div className="animate-fade-up flex flex-col items-center text-center">
        <InitialsAvatar name={profile.name} size={72} />
        <h1 className="mt-4 text-2xl font-semibold tracking-tight">{profile.name}</h1>
        <p style={{ color: "var(--text-faint)" }}>{profile.email}</p>
        {profile.emailVerified && (
          <span
            className="mt-2 flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-medium"
            style={{ background: "var(--emerald-soft)", color: "var(--emerald)" }}
          >
            <CheckIcon className="h-3 w-3" /> Verified
          </span>
        )}
      </div>

      <div className="glass animate-fade-up flex flex-col p-2" style={{ animationDelay: "80ms" }}>
        <Field label="Full name" value={profile.name} first />
        <Field label="Email" value={profile.email} />
        <Field label="User ID" value={profile.userId} mono />
      </div>

      {!editing && (
        <button
          type="button"
          onClick={startEditing}
          className="glass-hover animate-fade-up rounded-lg px-4 py-2.5 text-sm font-medium"
          style={{ animationDelay: "120ms", color: "var(--foreground)" }}
        >
          Change name or password
        </button>
      )}

      {editing && (
        <form
          onSubmit={onSubmit}
          className="glass animate-fade-up flex flex-col gap-4 p-6"
          style={{ animationDelay: "120ms" }}
        >
          <FormField label="Name" value={newName} onChange={(e) => setNewName(e.target.value)} />
          <FormField
            label="New password (leave blank to keep current)"
            type="password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
          />
          {newPassword.length > 0 && <PasswordRuleChecklist password={newPassword} rules={PASSWORD_RULES} />}
          <FormField
            label="Current password (required to confirm)"
            type="password"
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
            required
          />
          <ErrorText message={error} />
          <div className="flex gap-2">
            <SubmitButton disabled={submitting}>{submitting ? "Saving…" : "Save changes"}</SubmitButton>
            <button
              type="button"
              onClick={() => setEditing(false)}
              className="rounded-lg px-4 py-2.5 text-sm"
              style={{ color: "var(--text-faint)" }}
            >
              Cancel
            </button>
          </div>
        </form>
      )}
      {success && !editing && (
        <p className="animate-fade-in text-center text-sm" style={{ color: "var(--emerald)" }}>
          {success}
        </p>
      )}
    </div>
  );
}

function Field({ label, value, mono, first }: { label: string; value: string; mono?: boolean; first?: boolean }) {
  return (
    <div
      className="flex flex-col gap-0.5 px-4 py-3"
      style={{ borderTop: first ? "none" : "1px solid var(--border)" }}
    >
      <p className="text-xs uppercase tracking-wide" style={{ color: "var(--text-faint)" }}>{label}</p>
      <p className={mono ? "font-mono text-sm" : "text-sm"} style={{ color: "var(--foreground)" }}>{value}</p>
    </div>
  );
}

export default function ProfilePage() {
  return (
    <ProtectedRoute>
      <ProfileContent />
    </ProtectedRoute>
  );
}
