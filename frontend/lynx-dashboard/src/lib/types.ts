export interface TokenResponse {
  access_token: string;
  token_type: string;
  expires_in: number;
}

export interface RegisterResponse {
  userId: string;
  email: string;
  message: string;
}

export interface UserProfile {
  userId: string;
  email: string;
  name: string;
  emailVerified: boolean;
}

export interface Account {
  id: string;
  userId: string;
  currency: string;
  available: string;
  held: string;
  updatedAt: string;
}

export interface DepositAccepted {
  depositId: string;
  accountId: string;
}

export interface HistoryEntry {
  sagaId: string;
  entryType: string;
  accountId: string;
  amount: string;
  currency: string;
  createdAt: string;
}

export type SagaStatus = "HOLDING" | "SETTLED" | "FAILED" | "RELEASED" | string;

export interface TransferAccepted {
  sagaId: string;
  status: SagaStatus;
}

export interface TransferStatus {
  sagaId: string;
  status: SagaStatus;
  failureReason: string | null;
}

export interface SavedRecipient {
  id: string;
  label: string;
  recipientUserId: string;
  updatedAt: string;
}
