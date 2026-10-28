package com.tn.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import com.tn.resilience.ResilienceProperties.Settings;
import com.tn.resilience.ServiceUnavailableException.Reason;

class ResilienceInterceptorTest
{
  private static final String GROUP = "spot-service";
  private static final byte[] NO_BODY = new byte[0];

  @Test
  void shouldReturnAnswerWhenServiceAnswers() throws IOException
  {
    ResilienceInterceptor interceptor = interceptor(settings());
    ClientHttpResponse ok = response(HttpStatus.OK);

    assertSame(ok, interceptor.intercept(request(HttpMethod.GET), NO_BODY, (request, body) -> ok));
  }

  @Test
  void shouldRepeatReadWhenServiceFailsThenAnswers() throws IOException
  {
    ResilienceInterceptor interceptor = interceptor(settings());
    AtomicInteger calls = new AtomicInteger();

    ClientHttpResponse answer = interceptor.intercept(
      request(HttpMethod.GET),
      NO_BODY,
      (request, body) -> calls.incrementAndGet() < 3 ? response(HttpStatus.INTERNAL_SERVER_ERROR) : response(HttpStatus.OK)
    );

    assertEquals(HttpStatus.OK, answer.getStatusCode());
    assertEquals(3, calls.get());
  }

  @Test
  void shouldRepeatIdempotentWritesWhenServiceFails() throws IOException
  {
    for (HttpMethod method : new HttpMethod[] {HttpMethod.PUT, HttpMethod.DELETE})
    {
      ResilienceInterceptor interceptor = interceptor(settings());
      AtomicInteger calls = new AtomicInteger();

      interceptor.intercept(request(method), NO_BODY, (request, body) -> calls.incrementAndGet() < 2 ? response(HttpStatus.BAD_GATEWAY) : response(HttpStatus.NO_CONTENT));

      assertEquals(2, calls.get(), method.name());
    }
  }

  @Test
  void shouldNotRepeatCreateWhenServiceFails() throws IOException
  {
    for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PATCH})
    {
      ResilienceInterceptor interceptor = interceptor(settings());
      AtomicInteger calls = new AtomicInteger();

      ClientHttpResponse answer = interceptor.intercept(request(method), NO_BODY, (request, body) ->
      {
        calls.incrementAndGet();
        return response(HttpStatus.INTERNAL_SERVER_ERROR);
      });

      assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, answer.getStatusCode(), method.name());
      assertEquals(1, calls.get(), method.name());
    }
  }

  @Test
  void shouldReturnLastAnswerWhenEveryAttemptFails() throws IOException
  {
    ResilienceInterceptor interceptor = interceptor(settings());
    AtomicInteger calls = new AtomicInteger();

    ClientHttpResponse answer = interceptor.intercept(request(HttpMethod.GET), NO_BODY, (request, body) ->
    {
      calls.incrementAndGet();
      return response(HttpStatus.SERVICE_UNAVAILABLE);
    });

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, answer.getStatusCode());
    assertEquals(3, calls.get());
  }

  @Test
  void shouldThrowWhenServiceCannotBeReachedOnEveryAttempt()
  {
    ResilienceInterceptor interceptor = interceptor(settings());
    AtomicInteger calls = new AtomicInteger();

    assertThrows(SocketTimeoutException.class, () -> interceptor.intercept(request(HttpMethod.GET), NO_BODY, (request, body) ->
    {
      calls.incrementAndGet();
      throw new SocketTimeoutException("Read timed out");
    }));

    assertEquals(3, calls.get());
  }

  @Test
  void shouldNotCountClientErrorAgainstService() throws IOException
  {
    ResilienceInterceptor interceptor = interceptor(settings());
    AtomicInteger calls = new AtomicInteger();

    for (int i = 0; i < 50; i++)
    {
      ClientHttpResponse answer = interceptor.intercept(request(HttpMethod.GET), NO_BODY, (request, body) ->
      {
        calls.incrementAndGet();
        return response(HttpStatus.NOT_FOUND);
      });
      assertEquals(HttpStatus.NOT_FOUND, answer.getStatusCode());
    }

    assertEquals(50, calls.get());
  }

  @Test
  void shouldFailAtOnceWhenCircuitOpen() throws IOException
  {
    ResilienceInterceptor interceptor = interceptor(settings().withMaxAttempts(1).withOpenStateWait(Duration.ofSeconds(30)));
    AtomicInteger calls = new AtomicInteger();

    // enough failures to open the circuit
    for (int i = 0; i < 4; i++)
    {
      interceptor.intercept(request(HttpMethod.GET), NO_BODY, (request, body) ->
      {
        calls.incrementAndGet();
        return response(HttpStatus.INTERNAL_SERVER_ERROR);
      });
    }

    int callsBefore = calls.get();
    ServiceUnavailableException e = assertThrows(ServiceUnavailableException.class, () -> interceptor.intercept(request(HttpMethod.GET), NO_BODY, (request, body) ->
    {
      calls.incrementAndGet();
      return response(HttpStatus.OK);
    }));

    assertEquals(Reason.CIRCUIT_OPEN, e.reason());
    assertEquals(Duration.ofSeconds(30), e.retryAfter().orElseThrow());
    assertEquals(callsBefore, calls.get(), "the service must not be called while the circuit is open");
  }

  @Test
  void shouldTryServiceAgainWhenCircuitHasRested() throws Exception
  {
    ResilienceInterceptor interceptor = interceptor(settings().withMaxAttempts(1).withOpenStateWait(Duration.ofMillis(50)));

    for (int i = 0; i < 4; i++) interceptor.intercept(request(HttpMethod.GET), NO_BODY, (request, body) -> response(HttpStatus.INTERNAL_SERVER_ERROR));
    Thread.sleep(100);

    ClientHttpResponse answer = interceptor.intercept(request(HttpMethod.GET), NO_BODY, (request, body) -> response(HttpStatus.OK));

    assertEquals(HttpStatus.OK, answer.getStatusCode());
  }

  @Test
  void shouldRefuseCallWhenAllPlacesTaken() throws Exception
  {
    ResilienceInterceptor interceptor = interceptor(settings().withMaxConcurrentCalls(1).withBulkheadWait(Duration.ZERO));
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    CompletableFuture<ClientHttpResponse> slow = CompletableFuture.supplyAsync(() ->
    {
      try
      {
        return interceptor.intercept(request(HttpMethod.GET), NO_BODY, (request, body) ->
        {
          started.countDown();
          await(release);
          return response(HttpStatus.OK);
        });
      }
      catch (IOException e)
      {
        throw new IllegalStateException(e);
      }
    });
    started.await();

    ServiceUnavailableException e = assertThrows(ServiceUnavailableException.class, () -> interceptor.intercept(request(HttpMethod.GET), NO_BODY, (request, body) -> response(HttpStatus.OK)));
    release.countDown();

    assertEquals(Reason.BULKHEAD_FULL, e.reason());
    assertFalse(e.retryAfter().isPresent());
    assertEquals(HttpStatus.OK, slow.get().getStatusCode());
  }

  @Test
  void shouldNotLetOneServiceTakeEveryPlaceOfAnother() throws Exception
  {
    ResilienceInterceptor busy = new ResilienceInterceptor("busy", settings().withMaxConcurrentCalls(1).withBulkheadWait(Duration.ZERO).build());
    ResilienceInterceptor other = new ResilienceInterceptor("other", settings().withMaxConcurrentCalls(1).withBulkheadWait(Duration.ZERO).build());
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    CompletableFuture<Void> slow = CompletableFuture.runAsync(() ->
    {
      try
      {
        busy.intercept(request(HttpMethod.GET), NO_BODY, (request, body) ->
        {
          started.countDown();
          await(release);
          return response(HttpStatus.OK);
        });
      }
      catch (IOException e)
      {
        throw new IllegalStateException(e);
      }
    });
    started.await();

    ClientHttpResponse answer = other.intercept(request(HttpMethod.GET), NO_BODY, (request, body) -> response(HttpStatus.OK));
    release.countDown();
    slow.get();

    assertEquals(HttpStatus.OK, answer.getStatusCode());
  }

  @Test
  void shouldRefuseCallWhenRateLimitReached() throws IOException
  {
    ResilienceInterceptor interceptor = interceptor(settings().withCallsPerPeriod(2).withRateLimitPeriod(Duration.ofMinutes(1)));

    interceptor.intercept(request(HttpMethod.GET), NO_BODY, (request, body) -> response(HttpStatus.OK));
    interceptor.intercept(request(HttpMethod.GET), NO_BODY, (request, body) -> response(HttpStatus.OK));
    ServiceUnavailableException e = assertThrows(ServiceUnavailableException.class, () -> interceptor.intercept(request(HttpMethod.GET), NO_BODY, (request, body) -> response(HttpStatus.OK)));

    assertEquals(Reason.RATE_LIMITED, e.reason());
    assertEquals(Duration.ofMinutes(1), e.retryAfter().orElseThrow());
  }

  @Test
  void shouldNotRepeatWhenProtectionRefusesCall() throws IOException
  {
    ResilienceInterceptor interceptor = interceptor(settings().withCallsPerPeriod(1).withRateLimitPeriod(Duration.ofMinutes(1)));
    AtomicInteger calls = new AtomicInteger();

    interceptor.intercept(request(HttpMethod.GET), NO_BODY, (request, body) ->
    {
      calls.incrementAndGet();
      return response(HttpStatus.OK);
    });
    assertThrows(ServiceUnavailableException.class, () -> interceptor.intercept(request(HttpMethod.GET), NO_BODY, (request, body) ->
    {
      calls.incrementAndGet();
      return response(HttpStatus.OK);
    }));

    assertEquals(1, calls.get());
  }

  @Test
  void shouldTakeSettingsFromGroupThenDefaultsThenBuiltIn()
  {
    Settings own = new Settings(null, Duration.ofSeconds(30), null, null, null, null, null, null, null, null, null, null, null);
    Settings defaults = new Settings(Duration.ofSeconds(1), Duration.ofSeconds(9), null, null, null, null, null, null, null, null, null, null, null);
    ResilienceProperties properties = new ResilienceProperties(true, defaults, java.util.Map.of(GROUP, own));

    Settings settings = properties.forGroup(GROUP);

    assertEquals(Duration.ofSeconds(30), settings.readTimeout());
    assertEquals(Duration.ofSeconds(1), settings.connectTimeout());
    assertEquals(Settings.BUILT_IN.maxAttempts(), settings.maxAttempts());
    assertEquals(Duration.ofSeconds(9), properties.forGroup("another").readTimeout());
    assertTrue(properties.forGroup("another").maxConcurrentCalls() > 0);
  }

  @Test
  void shouldTryServiceWithTenCallsBeforeClosingUnlessSet()
  {
    Settings own = new Settings(null, null, null, null, null, null, 2, null, null, null, null, null, null);
    Settings defaults = new Settings(null, null, null, null, null, null, 4, null, null, null, null, null, null);
    ResilienceProperties properties = new ResilienceProperties(true, defaults, java.util.Map.of(GROUP, own));

    assertEquals(10, new ResilienceProperties(true, new Settings(null, null, null, null, null, null, null, null, null, null, null, null, null), java.util.Map.of()).forGroup(GROUP).halfOpenCalls());
    assertEquals(2, properties.forGroup(GROUP).halfOpenCalls());
    assertEquals(4, properties.forGroup("another").halfOpenCalls());
  }

  /** Quick to trip: four failures out of four calls open the circuit, and a repeat waits no time. */
  private static TestSettings settings()
  {
    return new TestSettings(Settings.BUILT_IN)
      .withFailureRateThreshold(50)
      .withSlidingWindowSize(4)
      .withMinimumNumberOfCalls(4)
      .withRetryWait(Duration.ofMillis(1))
      .withMaxConcurrentCalls(50)
      .withCallsPerPeriod(10_000);
  }

  private static ResilienceInterceptor interceptor(TestSettings settings)
  {
    return new ResilienceInterceptor(GROUP, settings.build());
  }

  private static void await(CountDownLatch latch) throws IOException
  {
    try
    {
      latch.await();
    }
    catch (InterruptedException e)
    {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  private static MockClientHttpRequest request(HttpMethod method)
  {
    return new MockClientHttpRequest(method, URI.create("http://localhost/v1/things"));
  }

  private static ClientHttpResponse response(HttpStatus status)
  {
    return new MockClientHttpResponse(NO_BODY, status);
  }
}
