package com.lynx.auth.service;

/**
 * Sends transactional email. One method today — an OTP code — kept
 * narrow rather than a generic "send any email" abstraction, since that's
 * the only kind of email this service sends.
 */
public interface MailService {

  void sendOtpEmail(String toEmail, String otpCode);
}
