package com.lynx.auth.repository;

import com.lynx.auth.domain.EmailOtp;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailOtpRepository extends JpaRepository<EmailOtp, UUID> {

  /** Most recent first — {@code OtpService} only ever needs to check the latest one sent. */
  List<EmailOtp> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
