package com.lynx.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  void generatesIdWhenHeaderAbsent() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    when(req.getHeader(SecurityHeaders.CORRELATION_ID)).thenReturn(null);

    // Capture the id visible in the MDC while the chain runs.
    AtomicReference<String> seenDuringChain = new AtomicReference<>();
    FilterChain chain = (rq, rs) -> seenDuringChain.set(CorrelationId.current());

    filter.doFilter(req, res, chain);

    assertNotNull(seenDuringChain.get());
    verify(res).setHeader(SecurityHeaders.CORRELATION_ID, seenDuringChain.get());
  }

  @Test
  void reusesInboundHeader() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    when(req.getHeader(SecurityHeaders.CORRELATION_ID)).thenReturn("edge-correlation-1");

    AtomicReference<String> seenDuringChain = new AtomicReference<>();
    FilterChain chain = (rq, rs) -> seenDuringChain.set(CorrelationId.current());

    filter.doFilter(req, res, chain);

    assertEquals("edge-correlation-1", seenDuringChain.get());
    verify(res).setHeader(SecurityHeaders.CORRELATION_ID, "edge-correlation-1");
  }

  @Test
  void clearsMdcAfterRequest() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    when(req.getHeader(SecurityHeaders.CORRELATION_ID)).thenReturn("x");

    filter.doFilter(req, res, (rq, rs) -> {
    });

    // Reused pool threads must not retain the previous request's id.
    assertNull(CorrelationId.current());
  }
}
