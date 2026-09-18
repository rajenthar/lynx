package com.lynx.auth.service;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends the OTP email through Spring's {@link JavaMailSender}, configured
 * (see {@code application.yml}'s {@code spring.mail.*}) to talk to Gmail's
 * SMTP submission server — see {@code docs/html/utils/smtp-gmail-flows.html}
 * for exactly how that handshake works and what each property means.
 */
public class SmtpMailService implements MailService {

  private final JavaMailSender mailSender;
  private final String fromAddress;

  public SmtpMailService(JavaMailSender mailSender, String fromAddress) {
    this.mailSender = mailSender;
    this.fromAddress = fromAddress;
  }

  @Override
  public void sendOtpEmail(String toEmail, String otpCode) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(fromAddress);
    message.setTo(toEmail);
    message.setSubject("Your Lynx verification code");
    message.setText("This code is from Lynx to verify your account.\n\n"
        + "Your verification code is: " + otpCode
        + "\n\nThis code expires in 10 minutes. If you didn't request this, ignore this email.");
    mailSender.send(message);
  }
}
