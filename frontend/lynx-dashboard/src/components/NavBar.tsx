"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import {
  DashboardIcon,
  WalletIcon,
  DepositIcon,
  TransferIcon,
  HistoryIcon,
  ProfileIcon,
  LogoutIcon,
  InitialsAvatar,
} from "@/components/Icons";

const LINKS = [
  { href: "/dashboard", label: "Dashboard", Icon: DashboardIcon },
  { href: "/accounts", label: "Accounts", Icon: WalletIcon },
  { href: "/deposit", label: "Deposit", Icon: DepositIcon },
  { href: "/transfer", label: "Transfer", Icon: TransferIcon },
  { href: "/history", label: "History", Icon: HistoryIcon },
  { href: "/profile", label: "Profile", Icon: ProfileIcon },
];

export function NavBar() {
  const { isAuthenticated, logout, profile } = useAuth();
  const pathname = usePathname();

  if (!isAuthenticated) return null;

  return (
    <header
      className="sticky top-0 z-40 backdrop-blur-xl"
      style={{ background: "rgba(5,7,13,0.75)", borderBottom: "1px solid var(--border)" }}
    >
      <div className="mx-auto flex max-w-5xl items-center gap-1 px-4 py-3">
        <Link href="/dashboard" className="mr-4 flex items-center gap-2 shrink-0">
          <div
            className="flex h-8 w-8 items-center justify-center rounded-lg text-sm font-bold text-[#04140d]"
            style={{ background: "linear-gradient(135deg, var(--brass), var(--emerald))" }}
          >
            L
          </div>
          <span className="hidden font-semibold tracking-tight sm:inline">Lynx</span>
        </Link>

        <nav className="flex flex-1 gap-1 overflow-x-auto">
          {LINKS.map((link) => {
            const active = pathname === link.href;
            return (
              <Link
                key={link.href}
                href={link.href}
                className="relative flex items-center gap-1.5 whitespace-nowrap rounded-lg px-3 py-1.5 text-sm font-medium transition-colors"
                style={{
                  color: active ? "var(--emerald)" : "var(--text-soft)",
                  background: active ? "var(--emerald-soft)" : "transparent",
                }}
              >
                <link.Icon className="h-4 w-4" />
                <span className="hidden md:inline">{link.label}</span>
              </Link>
            );
          })}
        </nav>

        <div className="ml-2 flex items-center gap-3">
          {profile && <InitialsAvatar name={profile.name} size={30} />}
          <button
            onClick={logout}
            title="Log out"
            className="flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-sm transition-colors hover:text-[var(--rose)]"
            style={{ color: "var(--text-faint)" }}
          >
            <LogoutIcon className="h-4 w-4" />
          </button>
        </div>
      </div>
    </header>
  );
}
