package com.lynx.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

import org.junit.jupiter.api.Test;

class TraceIdsTest {

  @Test
  void emptyWhenNoActiveSpan() {
    assertEquals("", TraceIds.currentTraceId());
    assertEquals("", TraceIds.currentSpanId());
  }

  @Test
  void readsRealTraceAndSpanIdWhileSpanIsActive() {
    // A real (in-memory, no exporter) SDK — enough to create genuine, correctly
    // formatted trace/span ids without configuring any collector.
    SdkTracerProvider provider = SdkTracerProvider.builder().build();
    OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    Tracer tracer = otel.getTracer("lynx-telemetry-test");

    Span span = tracer.spanBuilder("test-span").startSpan();
    try (Scope scope = span.makeCurrent()) {
      assertEquals(span.getSpanContext().getTraceId(), TraceIds.currentTraceId());
      assertEquals(span.getSpanContext().getSpanId(), TraceIds.currentSpanId());
      assertEquals(32, TraceIds.currentTraceId().length()); // OTel trace ids: 32 hex chars
      assertEquals(16, TraceIds.currentSpanId().length());  // OTel span ids: 16 hex chars
    } finally {
      span.end();
    }
  }

  @Test
  void emptyAgainAfterSpanEnds() {
    SdkTracerProvider provider = SdkTracerProvider.builder().build();
    OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    Tracer tracer = otel.getTracer("lynx-telemetry-test");

    Span span = tracer.spanBuilder("test-span").startSpan();
    try (Scope scope = span.makeCurrent()) {
      // active inside the scope
    } finally {
      span.end();
    }

    // scope closed -> no longer the "current" span on this thread
    assertEquals("", TraceIds.currentTraceId());
  }
}
