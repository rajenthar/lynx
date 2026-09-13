package com.lynx.auth.api;

import com.lynx.auth.dto.AuthDtos.LoginRequest;
import com.lynx.auth.dto.AuthDtos.RegisterRequest;
import com.lynx.auth.dto.AuthDtos.TokenResponse;
import com.lynx.auth.service.JwtIssuer;
import com.lynx.auth.service.UserAuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * End-user registration and login (other-docs/11 Decision 1). Both public —
 * no {@code Authorization} header expected on either call, unlike every
 * other Lynx controller.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

  private final UserAuthService userAuthService;

  public AuthController(UserAuthService userAuthService) {
    this.userAuthService = userAuthService;
  }

  @PostMapping("/register")
  public TokenResponse register(@RequestBody RegisterRequest request) {
    JwtIssuer.IssuedToken token =
        userAuthService.register(request.email(), request.password(), request.name());
    return TokenResponse.bearer(token.value(), token.expiresInSeconds());
  }

  @PostMapping("/login")
  public TokenResponse login(@RequestBody LoginRequest request) {
    JwtIssuer.IssuedToken token = userAuthService.login(request.email(), request.password());
    return TokenResponse.bearer(token.value(), token.expiresInSeconds());
  }
}
