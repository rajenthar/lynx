type IconProps = { className?: string; style?: React.CSSProperties };

const base = "h-5 w-5";

export function DashboardIcon({ className = base, style }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} style={style}>
      <path d="M4 13h6V4H4v9Zm10 7h6V4h-6v16ZM4 20h6v-5H4v5Z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
    </svg>
  );
}

export function WalletIcon({ className = base, style }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} style={style}>
      <path d="M3 7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v1h1a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H5a2 2 0 0 1-2-2V7Z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
      <circle cx="16.5" cy="13" r="1.4" fill="currentColor" />
    </svg>
  );
}

export function DepositIcon({ className = base, style }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} style={style}>
      <path d="M12 4v11m0 0 4-4m-4 4-4-4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M5 15v3a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-3" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function TransferIcon({ className = base, style }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} style={style}>
      <path d="M4 8h13m0 0-4-4m4 4-4 4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M20 16H7m0 0 4-4m-4 4 4 4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function HistoryIcon({ className = base, style }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} style={style}>
      <path d="M4 4v5h5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M4.6 15a8 8 0 1 0 1.5-8.5L4 9" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M12 8v5l3 2" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function ProfileIcon({ className = base, style }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} style={style}>
      <circle cx="12" cy="8" r="3.2" stroke="currentColor" strokeWidth="1.6" />
      <path d="M5 20c1.2-3.6 4-5.4 7-5.4s5.8 1.8 7 5.4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}

export function LogoutIcon({ className = base, style }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} style={style}>
      <path d="M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M16 8l4 4-4 4M20 12H9" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function StarIcon({ className = base, style, filled = false }: IconProps & { filled?: boolean }) {
  return (
    <svg viewBox="0 0 24 24" fill={filled ? "currentColor" : "none"} className={className} style={style}>
      <path d="M12 3.5l2.6 5.4 5.9.7-4.3 4.2 1 5.9-5.2-2.8-5.2 2.8 1-5.9-4.3-4.2 5.9-.7L12 3.5Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
    </svg>
  );
}

export function TrashIcon({ className = base, style }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} style={style}>
      <path d="M4 7h16M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2m-9 0 1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function PlusIcon({ className = base, style }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} style={style}>
      <path d="M12 5v14M5 12h14" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}

export function CheckIcon({ className = base, style }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} style={style}>
      <path d="M4 12.5l5 5L20 6.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

/** Animated success — a circle drawing itself, then a checkmark drawing inside it. */
export function AnimatedCheck({ className = "h-16 w-16", color = "var(--emerald)" }: { className?: string; color?: string }) {
  return (
    <svg viewBox="0 0 52 52" className={className}>
      <circle className="checkmark-circle" cx="26" cy="26" r="24" fill="none" stroke={color} strokeWidth="2.5" />
      <path className="checkmark-check" fill="none" stroke={color} strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" d="M14 27l7 7 16-16" />
    </svg>
  );
}

const CURRENCY_SYMBOLS: Record<string, string> = {
  SGD: "S$", USD: "$", EUR: "€", GBP: "£", JPY: "¥", AUD: "A$", INR: "₹",
};

/** A small circular currency badge — deterministic color per currency, symbol inside. */
export function CurrencyBadge({ currency, size = 40 }: { currency: string; size?: number }) {
  const hues: Record<string, string> = {
    SGD: "linear-gradient(135deg, #f87171, #dc2626)",
    USD: "linear-gradient(135deg, #34d399, #059669)",
    EUR: "linear-gradient(135deg, #7dd3fc, #0284c7)",
    GBP: "linear-gradient(135deg, #d8b978, #a3773f)",
    JPY: "linear-gradient(135deg, #f472b6, #db2777)",
    AUD: "linear-gradient(135deg, #fbbf24, #d97706)",
    INR: "linear-gradient(135deg, #a78bfa, #7c3aed)",
  };
  const symbol = CURRENCY_SYMBOLS[currency] ?? currency.slice(0, 1);
  return (
    <div
      style={{ width: size, height: size, background: hues[currency] ?? "linear-gradient(135deg, #6b7488, #4b5563)" }}
      className="flex items-center justify-center rounded-full text-sm font-semibold text-white shadow-lg shrink-0"
    >
      {symbol}
    </div>
  );
}

/** Initials avatar for profile/nav — deterministic gradient from the name. */
export function InitialsAvatar({ name, size = 40 }: { name: string; size?: number }) {
  const initials = name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase() ?? "")
    .join("") || "?";
  return (
    <div
      style={{
        width: size,
        height: size,
        fontSize: size * 0.38,
        background: "linear-gradient(135deg, var(--brass), var(--emerald))",
      }}
      className="flex items-center justify-center rounded-full font-semibold text-[#04140d] shrink-0"
    >
      {initials}
    </div>
  );
}
