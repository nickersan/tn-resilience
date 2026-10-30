package com.tn.resilience;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.actuate.endpoint.Access;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * The state of the protection of every service this service calls, as {@code /actuator/resilience}. Reading it never counts as a call,
 * and it is not part of the service's health.
 *
 * <p>It can also close the circuit breakers, with a {@code POST} to {@code /actuator/resilience} (all of them) or to
 * {@code /actuator/resilience/{group}} (one): for an environment in which a service is stopped and started on purpose and a caller's circuit
 * must not be left to find out by a call that the service is back. That is off unless {@code management.endpoint.resilience.access} is
 * {@code unrestricted}; the endpoint is otherwise read-only.
 */
@Slf4j
@Endpoint(id = "resilience", defaultAccess = Access.READ_ONLY)
public class ResilienceEndpoint
{
  private final ResilienceGroups groups;

  public ResilienceEndpoint(ResilienceGroups groups)
  {
    this.groups = groups;
  }

  @ReadOperation
  public Report resilience()
  {
    Map<String, Group> report = new LinkedHashMap<>();

    for (ResilienceInterceptor interceptor : groups.interceptors())
    {
      report.put(interceptor.group(), group(interceptor.circuitBreaker(), interceptor.bulkhead(), interceptor.rateLimiter()));
    }

    return new Report(report);
  }

  /** Closes every circuit breaker, forgetting the calls it has counted; the state of the protection afterwards. */
  @WriteOperation
  public Report reset()
  {
    for (ResilienceInterceptor interceptor : groups.interceptors()) reset(interceptor);

    return resilience();
  }

  /** Closes the circuit breaker of one group, forgetting the calls it has counted; the state of the protection afterwards. */
  @WriteOperation
  public Report reset(@Selector String group)
  {
    ResilienceInterceptor interceptor = groups.interceptors().stream()
      .filter(candidate -> candidate.group().equals(group))
      .findFirst()
      .orElseThrow(() -> new InvalidEndpointRequestException("No group called " + group, "no such group"));
    reset(interceptor);

    return resilience();
  }

  private static void reset(ResilienceInterceptor interceptor)
  {
    interceptor.circuitBreaker().reset();
    log.info("Circuit breaker of {} reset", interceptor.group());
  }

  private static Group group(CircuitBreaker circuitBreaker, Bulkhead bulkhead, RateLimiter rateLimiter)
  {
    CircuitBreaker.Metrics circuit = circuitBreaker.getMetrics();
    float failureRate = circuit.getFailureRate();

    return new Group(
      new Circuit(
        circuitBreaker.getState().name(),
        failureRate < 0 ? null : failureRate,
        circuit.getNumberOfBufferedCalls(),
        circuit.getNumberOfFailedCalls(),
        circuit.getNumberOfNotPermittedCalls()
      ),
      new Places(bulkhead.getMetrics().getAvailableConcurrentCalls(), bulkhead.getMetrics().getMaxAllowedConcurrentCalls()),
      new Permissions(rateLimiter.getMetrics().getAvailablePermissions())
    );
  }

  /** @param groups the protection of each service called, by group name; empty when the service calls no other */
  public record Report(Map<String, Group> groups) {}

  public record Group(Circuit circuitBreaker, Places bulkhead, Permissions rateLimiter) {}

  /** @param failureRate percent of recent calls that failed, or null until enough calls have been seen */
  public record Circuit(String state, Float failureRate, int bufferedCalls, int failedCalls, long notPermittedCalls) {}

  public record Places(int availableConcurrentCalls, int maxAllowedConcurrentCalls) {}

  public record Permissions(int availablePermissions) {}
}
