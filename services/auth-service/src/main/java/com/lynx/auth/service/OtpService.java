package com.lynx.auth.service;

import com.lynx.auth.domain.EmailOtp;
import com.lynx.auth.domain.User;
import com.lynx.auth.repository.EmailOtpRepository;
import com.lynx.auth.repository.UserRepository;
import com.lynx.common.error.NotFoundException;
import com.lynx.common.error.ValidationException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates, sends, and verifies the 6-digit email OTP used to confirm a
 * registration actually belongs to the email address it claims — closes
 * item 10 from the frontend feature list (verify the right mail user is
 * trying to use). Not a general 2FA/login-step-up mechanism — purely a
 * one-time registration gate.
 */
public class OtpService {

  private static final Logger log = LoggerFactory.getLogger(OtpService.class);

  private static final int CODE_LENGTH = 6;
  private static final Duration TTL = Duration.ofMinutes(10);

  private final EmailOtpRepository otpRepository;
  private final UserRepository userRepository;
  private final MailService mailService;
  private final SecureRandom random = new SecureRandom();

  public OtpService(EmailOtpRepository otpRepository, UserRepository userRepository, MailService mailService) {
    this.otpRepository = otpRepository;
    this.userRepository = userRepository;
    this.mailService = mailService;
  }

  /**
   * Generates a fresh code and stores it — used at registration and on
   * resend. The email send is deliberately best-effort: the code already
   * exists in the database the moment this returns, so a transient SMTP
   * failure (bad credentials, Gmail unreachable) never fails the caller's
   * registration/resend request outright — {@code /auth/resend-otp} is the
   * built-in recovery path if the email genuinely never arrived.
   */
  public void issueAndSend(User user) {
    String code = generateCode();
    Instant now = Instant.now();
    EmailOtp otp = new EmailOtp(UUID.randomUUID(), user.getId(), code, now.plus(TTL), now);
    otpRepository.save(otp);
    try {
      mailService.sendOtpEmail(user.getEmail(), code);
    } catch (Exception e) {
      log.warn("Failed to send OTP email to {} — the code is saved; resend-otp can retry", user.getEmail(), e);
    }
  }

  /** Regenerates and resends for an already-registered, not-yet-verified email. */
  public void resend(String email) {
    User user = userRepository.findByEmail(normalize(email))
        .orElseThrow(() -> new NotFoundException("No pending registration for this email"));
    if (user.isEmailVerified()) {
      throw new ValidationException("This email is already verified");
    }
    issueAndSend(user);
  }

  /**
   * Verifies a submitted code against the most recently issued one for that
   * email — an older, still-unexpired code from an earlier resend is
   * deliberately NOT accepted, matching how most OTP flows only honor the
   * latest code sent.
   */
  public User verify(String email, String code) {
    User user = userRepository.findByEmail(normalize(email))
        .orElseThrow(() -> new NotFoundException("No pending registration for this email"));
    if (user.isEmailVerified()) {
      throw new ValidationException("This email is already verified");
    }
    List<EmailOtp> candidates = otpRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
    if (candidates.isEmpty()) {
      throw new ValidationException("No verification code was sent — request a new one");
    }
    EmailOtp latest = candidates.get(0);
    Instant now = Instant.now();
    if (!latest.isUsable(code, now)) {
      throw new ValidationException("Invalid or expired verification code");
    }
    latest.markConsumed(now);
    otpRepository.save(latest);
    user.markEmailVerified(now);
    userRepository.save(user);
    return user;
  }

  private String generateCode() {
    int bound = (int) Math.pow(10, CODE_LENGTH);
    int value = random.nextInt(bound);
    return String.format("%0" + CODE_LENGTH + "d", value);
  }

  private static String normalize(String email) {
    return email == null ? null : email.strip().toLowerCase();
  }
}
