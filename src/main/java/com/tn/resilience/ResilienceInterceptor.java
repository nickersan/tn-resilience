package com.tn.resilience;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import com.tn.resilience.ResilienceProperties.Settings;
import com.tn.resilience.ServiceUnavailableException.Reason;

/**
 * Protects the calls made to one service: a rate limiter, a bulkhead and a circuit breaker, and for calls that are safe to repeat
 * a retry around them all, so each attempt is limited, given a place and counted.
 *
 * <p>A call fails, for the circuit breaker and the retry, when it cannot be completed or the service answers with a 5xx. Any other
 * answer, a 4xx included, is the service doing its job: it is returned and never counted against it. A call the protection refuses
 * is a {@link ServiceUnavailableException}; a service that answers with a 5xx on the last attempt is returned as it answered.
 *
 * <p>Which calls are safe to repeat is decided by the method: reads, {@code PUT} and {@code DELETE}, which are idempotent by
 * definition. A {@code POST} or {@code PATCH} is never repeated, since it may do its work twice.
 */
@Slf4j
public class ResilienceInterceptor implements ClientHttpRequestInterceptor
{
  private static final Set<HttpMethod> REPEATABLE = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.PUT, HttpMethod.DELETE);

  private final String group;
  private final CircuitBreaker circuitBreaker;
  private final Bulkhead bulkhead;
  private final RateLimiter rateLimiter;
  private final Retry retry;
  private final Duration circuitOpenWait;
  private final Duration rateLimitPeriod;

  public ResilienceInterceptor(String group, Settings settings)
  {
    this.group = group;
    this.circuitOpenWait = settings.openStateWait();
    this.rateLimitPeriod = settings.rateLimitPeriod();

    circuitBreaker = CircuitBreaker.of(
      group,
      CircuitBreakerConfig.custom()
        .failureRateThreshold(settings.failureRateThreshold())
        .slidingWindowSize(settings.slidingWindowSize())
        .minimumNumberOfCalls(settings.minimumNumberOfCalls())
        .waitDurationInOpenState(settings.openStateWait())
        .build()
    );

    bulkhead = Bulkhead.of(
      group,
      BulkheadConfig.custom()
        .maxConcurrentCalls(settings.maxConcurrentCalls())
        .maxWaitDuration(settings.bulkheadWait())
        .build()
    );

    rateLimiter = RateLimiter.of(
      group,
      RateLimiterConfig.custom()
        .limitForPeriod(settings.callsPerPeriod())
        .limitRefreshPeriod(settings.rateLimitPeriod())
        .timeoutDuration(Duration.ZERO)
        .build()
    );

    retry = Retry.of(
      group,
      RetryConfig.custom()
        .maxAttempts(settings.maxAttempts())
        .waitDuration(settings.retryWait())
        .retryOnException(e -> e instanceof ServerFailure || e instanceof UncheckedIOException)
        .build()
    );

    // an attempt that is going to be repeated is over: let go of its connection
    retry.getEventPublisher().onRetry(event ->
    {
      if (event.getLastThrowable() instanceof ServerFailure failure) failure.response.close();
    });
  }

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException
  {
    Supplier<ClientHttpResponse> call = () -> attempt(request, body, execution);
    call = CircuitBreaker.decorateSupplier(circuitBreaker, call);
    call = Bulkhead.decorateSupplier(bulkhead, call);
    call = RateLimiter.decorateSupplier(rateLimiter, call);
    if (REPEATABLE.contains(request.getMethod())) call = Retry.decorateSupplier(retry, call);

    try
    {
      return call.get();
    }
    catch (ServerFailure e)
    {
      return e.response;
    }
    catch (UncheckedIOException e)
    {
      throw e.getCause();
    }
    catch (CallNotPermittedException e)
    {
      throw unavailable(Reason.CIRCUIT_OPEN, circuitOpenWait, e);
    }
    catch (BulkheadFullException e)
    {
      throw unavailable(Reason.BULKHEAD_FULL, null, e);
    }
    catch (RequestNotPermitted e)
    {
      throw unavailable(Reason.RATE_LIMITED, rateLimitPeriod, e);
    }
  }

  private ClientHttpResponse attempt(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
  {
    try
    {
      ClientHttpResponse response = execution.execute(request, body);
      if (response.getStatusCode().is5xxServerError()) throw new ServerFailure(response);

      return response;
    }
    catch (IOException e)
    {
      throw new UncheckedIOException(e);
    }
  }

  private ServiceUnavailableException unavailable(Reason reason, Duration retryAfter, Throwable cause)
  {
    log.warn("Call to {} refused: {}", group, reason);

    return new ServiceUnavailableException(group, reason, retryAfter, cause);
  }

  /** Carries a 5xx answer through the protection as a failure, so that it is counted and can be repeated, and is then returned as it was. */
  private static class ServerFailure extends RuntimeException
  {
    private final transient ClientHttpResponse response;

    ServerFailure(ClientHttpResponse response)
    {
      super(null, null, false, false);
      this.response = response;
    }
  }
}
