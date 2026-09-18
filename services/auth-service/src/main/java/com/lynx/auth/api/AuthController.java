package com.lynx.auth.api;

import com.lynx.auth.client.AccountServiceClient;
import com.lynx.auth.config.JwtAuthFilter;
import com.lynx.auth.domain.User;
import com.lynx.auth.dto.AuthDtos.ChangeDetailsRequest;
import com.lynx.auth.dto.AuthDtos.LoginRequest;
import com.lynx.auth.dto.AuthDtos.RegisterRequest;
import com.lynx.auth.dto.AuthDtos.RegisterResponse;
import com.lynx.auth.dto.AuthDtos.ResendOtpRequest;
import com.lynx.auth.dto.AuthDtos.TokenResponse;
import com.lynx.auth.dto.AuthDtos.UserProfileView;
import com.lynx.auth.dto.AuthDtos.VerifyOtpRequest;
import com.lynx.auth.repository.UserRepository;
import com.lynx.auth.service.JwtIssuer;
import com.lynx.auth.service.OtpService;
import com.lynx.auth.service.UserAuthService;
import com.lynx.common.error.NotFoundException;
import com.lynx.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * End-user registration, OTP verification, and login. {@code /register},
 * {@code /login}, {@code /verify-otp}, and {@code /resend-otp} are all
 * public — no {@code Authorization} header expected — unlike every other
 * Lynx controller. {@code /me} is the one exception: it requires a bearer
 * JWT, verified by {@code JwtAuthFilter} (registered only for this path —
 * see {@code WebConfig}).
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

  private final UserAuthService userAuthService;
  private final OtpService otpService;
  private final UserRepository userRepository;
  private final AccountServiceClient accountServiceClient;

  public AuthController(
      UserAuthService userAuthService, OtpService otpService, UserRepository userRepository,
      AccountServiceClient accountServiceClient) {
    this.userAuthService = userAuthService;
    this.otpService = otpService;
    this.userRepository = userRepository;
    this.accountServiceClient = accountServiceClient;
  }

  @PostMapping("/register")
  public ResponseEntity<RegisterResponse> register(@RequestBody RegisterRequest request) {
    User user = userAuthService.register(request.email(), request.password(), request.name());
    RegisterResponse body = new RegisterResponse(
        user.getId().toString(), user.getEmail(),
        "Verification code sent — check your email and call /auth/verify-otp to finish signing up.");
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
  }

  @PostMapping("/verify-otp")
  public TokenResponse verifyOtp(@RequestBody VerifyOtpRequest request) {
    User user = otpService.verify(request.email(), request.code());
    JwtIssuer.IssuedToken token = userAuthService.issueTokenForVerifiedUser(user);
    // Best-effort (see AccountServiceClient's own javadoc) — a starter SGD
    // account so the dashboard isn't an empty state right after signup.
    accountServiceClient.seedDefaultAccount(user.getId());
    return TokenResponse.bearer(token.value(), token.expiresInSeconds());
  }

  @PostMapping("/resend-otp")
  public void resendOtp(@RequestBody ResendOtpRequest request) {
    otpService.resend(request.email());
  }

  @PostMapping("/login")
  public TokenResponse login(@RequestBody LoginRequest request) {
    JwtIssuer.IssuedToken token = userAuthService.login(request.email(), request.password());
    return TokenResponse.bearer(token.value(), token.expiresInSeconds());
  }

  @GetMapping("/me")
  public UserProfileView me(HttpServletRequest request) {
    UserContext caller = (UserContext) request.getAttribute(JwtAuthFilter.USER_CONTEXT_ATTRIBUTE);
    User user = userRepository.findById(UUID.fromString(caller.userId()))
        .orElseThrow(() -> new NotFoundException("User not found"));
    return new UserProfileView(user.getId().toString(), user.getEmail(), user.getName(), user.isEmailVerified());
  }

  @PatchMapping("/me")
  public UserProfileView changeDetails(@RequestBody ChangeDetailsRequest request, HttpServletRequest httpRequest) {
    UserContext caller = (UserContext) httpRequest.getAttribute(JwtAuthFilter.USER_CONTEXT_ATTRIBUTE);
    User user = userAuthService.changeDetails(
        UUID.fromString(caller.userId()), request.currentPassword(), request.newName(), request.newPassword());
    return new UserProfileView(user.getId().toString(), user.getEmail(), user.getName(), user.isEmailVerified());
  }
}
