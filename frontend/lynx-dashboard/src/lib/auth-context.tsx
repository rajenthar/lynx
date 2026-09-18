"use client";

import { createContext, useCallback, useContext, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, getToken, setToken, setUnauthorizedHandler } from "./api";
import type { RegisterResponse, TokenResponse, UserProfile } from "./types";

interface AuthContextValue {
  isAuthenticated: boolean;
  profile: UserProfile | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, name: string) => Promise<RegisterResponse>;
  verifyOtp: (email: string, code: string) => Promise<void>;
  resendOtp: (email: string) => Promise<void>;
  logout: () => void;
  refreshProfile: () => Promise<void>;
  changeDetails: (currentPassword: string, newName?: string, newPassword?: string) => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const router = useRouter();

  const refreshProfile = useCallback(async () => {
    if (!getToken()) {
      setProfile(null);
      return;
    }
    try {
      const me = await api.get<UserProfile>("/auth/me");
      setProfile(me);
    } catch {
      setToken(null);
      setProfile(null);
    }
  }, []);

  const logout = useCallback(() => {
    setToken(null);
    setProfile(null);
    router.replace("/login");
  }, [router]);

  // Registered before the mount-time refreshProfile effect below (effects
  // run in declaration order within a commit) — so even that very first
  // /auth/me probe is covered if it comes back 401 for a stale token still
  // sitting in localStorage from a previous session.
  useEffect(() => {
    setUnauthorizedHandler(logout);
  }, [logout]);

  useEffect(() => {
    let cancelled = false;
    async function run() {
      await refreshProfile();
      if (!cancelled) setLoading(false);
    }
    run();
    return () => {
      cancelled = true;
    };
  }, [refreshProfile]);

  const login = useCallback(
    async (email: string, password: string) => {
      const response = await api.post<TokenResponse>("/auth/login", { email, password });
      setToken(response.access_token);
      await refreshProfile();
    },
    [refreshProfile],
  );

  const register = useCallback(async (email: string, password: string, name: string) => {
    return api.post<RegisterResponse>("/auth/register", { email, password, name });
  }, []);

  const verifyOtp = useCallback(
    async (email: string, code: string) => {
      const response = await api.post<TokenResponse>("/auth/verify-otp", { email, code });
      setToken(response.access_token);
      await refreshProfile();
    },
    [refreshProfile],
  );

  const resendOtp = useCallback(async (email: string) => {
    await api.post<void>("/auth/resend-otp", { email });
  }, []);

  // The backend checks currentPassword before touching anything (see
  // UserAuthService.changeDetails) — this never sends a request that could
  // partially apply, it's all-or-nothing server-side.
  const changeDetails = useCallback(
    async (currentPassword: string, newName?: string, newPassword?: string) => {
      const updated = await api.patch<UserProfile>("/auth/me", { currentPassword, newName, newPassword });
      setProfile(updated);
    },
    [],
  );

  return (
    <AuthContext.Provider
      value={{
        isAuthenticated: profile !== null,
        profile,
        loading,
        login,
        register,
        verifyOtp,
        resendOtp,
        logout,
        refreshProfile,
        changeDetails,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
