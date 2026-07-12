package com.lynx.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynx.common.error.AuthException;
import com.lynx.common.error.ErrorCode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UserContextTest {

  @Test
  void hasRoleReflectsGrantedRoles() {
    UserContext ctx = new UserContext("user-1", "a@b.test", Set.of("USER"));
    assertTrue(ctx.hasRole("USER"));
    assertFalse(ctx.hasRole("ADMIN"));
  }

  @Test
  void requireRolePassesWhenGranted() {
    UserContext ctx = new UserContext("user-1", null, Set.of("ADMIN"));
    ctx.requireRole("ADMIN"); // does not throw
  }

  @Test
  void requireRoleThrowsForbiddenWhenMissing() {
    UserContext ctx = new UserContext("user-1", null, Set.of("USER"));
    AuthException e = assertThrows(AuthException.class, () -> ctx.requireRole("ADMIN"));
    assertEquals(ErrorCode.FORBIDDEN, e.code());
  }

  @Test
  void nullRolesBecomeEmptyImmutableSet() {
    UserContext ctx = new UserContext("user-1", null, null);
    assertTrue(ctx.roles().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> ctx.roles().add("X"));
  }

  @Test
  void nullUserIdRejected() {
    assertThrows(NullPointerException.class,
        () -> new UserContext(null, "a@b.test", Set.of()));
  }
}
