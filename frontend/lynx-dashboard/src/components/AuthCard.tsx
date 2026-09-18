export function AuthCard({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="mx-auto flex min-h-[80vh] max-w-sm flex-col justify-center px-2">
      <div className="mb-8 animate-fade-up text-center">
        <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-2xl text-xl font-bold text-[#04140d] shadow-lg"
             style={{ background: "linear-gradient(135deg, var(--brass), var(--emerald))" }}>
          L
        </div>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {subtitle && <p className="mt-1.5 text-sm" style={{ color: "var(--text-soft)" }}>{subtitle}</p>}
      </div>
      <div className="glass animate-fade-up flex flex-col gap-4 p-7" style={{ animationDelay: "80ms" }}>
        {children}
      </div>
    </div>
  );
}

export function FormField({
  label,
  ...props
}: React.InputHTMLAttributes<HTMLInputElement> & { label: string }) {
  return (
    <label className="flex flex-col gap-1.5 text-sm" style={{ color: "var(--text-soft)" }}>
      {label}
      <input
        {...props}
        className="rounded-lg px-3.5 py-2.5 text-sm outline-none transition-colors"
        style={{
          background: "var(--surface)",
          border: "1px solid var(--border)",
          color: "var(--foreground)",
        }}
        onFocus={(e) => (e.currentTarget.style.borderColor = "var(--emerald)")}
        onBlur={(e) => (e.currentTarget.style.borderColor = "var(--border)")}
      />
    </label>
  );
}

export function PasswordRuleChecklist({
  password,
  rules,
}: {
  password: string;
  rules: { key: string; label: string; test: (p: string) => boolean }[];
}) {
  return (
    <ul className="flex flex-col gap-1 text-xs">
      {rules.map((rule) => {
        const met = rule.test(password);
        return (
          <li
            key={rule.key}
            className="flex items-center gap-1.5 transition-colors"
            style={{ color: met ? "var(--emerald)" : "var(--text-faint)" }}
          >
            <span aria-hidden>{met ? "✓" : "○"}</span> {rule.label}
          </li>
        );
      })}
    </ul>
  );
}

export function ErrorText({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <p className="animate-fade-in flex items-center gap-1.5 text-sm" style={{ color: "var(--rose)" }}>
      <span aria-hidden>⚠</span> {message}
    </p>
  );
}

export function SubmitButton({
  children,
  disabled,
}: {
  children: React.ReactNode;
  disabled?: boolean;
}) {
  return (
    <button type="submit" disabled={disabled} className="btn-primary mt-1 rounded-lg px-4 py-2.5 text-sm">
      {children}
    </button>
  );
}
