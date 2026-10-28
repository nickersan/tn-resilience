package com.tn.resilience;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * The state of the protection of every service this service calls, as {@code /actuator/resilience}. It only reads: looking never
 * counts as a call, and it is not part of the service's health.
 */
@Endpoint(id = "resilience")
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
