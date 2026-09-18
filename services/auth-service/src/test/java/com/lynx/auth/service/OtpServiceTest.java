package com.lynx.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynx.auth.domain.EmailOtp;
import com.lynx.auth.domain.User;
import com.lynx.auth.repository.EmailOtpRepository;
import com.lynx.auth.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OtpServiceTest {

  private EmailOtpRepository otpRepository;
  private UserRepository userRepository;
  private OtpService otpService;

  @BeforeEach
  void setUp() {
    otpRepository = mock(EmailOtpRepository.class);
    when(otpRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    userRepository = mock(UserRepository.class);
  }

  @Test
  void issueAndSendSavesTheCodeEvenWhenTheMailSendThrows() {
    MailService failingMailService = (toEmail, otpCode) -> {
      throw new RuntimeException("smtp unreachable");
    };
    otpService = new OtpService(otpRepository, userRepository, failingMailService);
    User user = new User(UUID.randomUUID(), "a@b.com", "Alice", "hash", Instant.now());

    otpService.issueAndSend(user);

    var captor = org.mockito.ArgumentCaptor.forClass(EmailOtp.class);
    verify(otpRepository).save(captor.capture());
    assertThat(captor.getValue().getUserId()).isEqualTo(user.getId());
  }
}
