/** Mirrors auth-service's own UserAuthService.validatePassword exactly — kept
 * in sync by hand since there's no shared schema between the two. */
export const MIN_PASSWORD_LENGTH = 8;

export interface PasswordRule {
  key: string;
  label: string;
  test: (password: string) => boolean;
}

export const PASSWORD_RULES: PasswordRule[] = [
  {
    key: "length",
    label: `At least ${MIN_PASSWORD_LENGTH} characters`,
    test: (p) => p.length >= MIN_PASSWORD_LENGTH,
  },
  {
    key: "number",
    label: "At least one number",
    test: (p) => /[0-9]/.test(p),
  },
  {
    key: "special",
    label: "At least one special character",
    test: (p) => /[^a-zA-Z0-9]/.test(p),
  },
];

export function isPasswordValid(password: string): boolean {
  return PASSWORD_RULES.every((rule) => rule.test(password));
}
