package com.tn.resilience;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How calls to other services are protected, set once for all of them in {@code defaults} and overridden for one service by the
 * name of its HTTP service group in {@code groups}:
 *
 * <pre>
 * tn:
 *   resilience:
 *     defaults:
 *       read-timeout: 5s
 *     groups:
 *       spot-service:
 *         read-timeout: 10s
 * </pre>
 *
 * A setting left out takes the group's value in {@code defaults}, and failing that the built-in value.
 *
 * @param enabled  whether to protect calls at all
 * @param defaults the settings every group starts from
 * @param groups   settings for particular groups, by group name
 */
@ConfigurationProperties("tn.resilience")
public record ResilienceProperties(
  @DefaultValue("true") boolean enabled,
  @DefaultValue Settings defaults,
  @DefaultValue Map<String, Settings> groups
)
{
  /** The settings in force for a group: its own, then the defaults, then the built-in values. */
  public Settings forGroup(String group)
  {
    Settings own = groups.get(group);
    return (own == null ? defaults : own.orElse(defaults)).orElse(Settings.BUILT_IN);
  }

  /**
   * Every setting is optional, so that a group can name only what it changes.
   *
   * @param connectTimeout          how long to wait to connect to the service
   * @param readTimeout             how long to wait for the service to answer once connected
   * @param failureRateThreshold    the percentage of recent calls that fail, from 1 to 100, at which the circuit opens
   * @param slidingWindowSize       how many recent calls the circuit breaker looks at
   * @param minimumNumberOfCalls    how many calls must be seen before the circuit can open
   * @param openStateWait           how long the circuit stays open before trying the service again
   * @param halfOpenCalls          how many calls the circuit lets through to try the service once it has waited; it closes when they have all gone well
   * @param maxConcurrentCalls      how many calls to the service may be in progress at once
   * @param bulkheadWait            how long a call waits for a place when all are taken, before failing
   * @param callsPerPeriod          how many calls to the service are allowed in each rate limit period
   * @param rateLimitPeriod         the length of the rate limit period
   * @param maxAttempts             how many times a call that is safe to repeat is tried in all
   * @param retryWait               how long to wait between attempts
   */
  public record Settings(
    Duration connectTimeout,
    Duration readTimeout,
    Integer failureRateThreshold,
    Integer slidingWindowSize,
    Integer minimumNumberOfCalls,
    Duration openStateWait,
    Integer halfOpenCalls,
    Integer maxConcurrentCalls,
    Duration bulkheadWait,
    Integer callsPerPeriod,
    Duration rateLimitPeriod,
    Integer maxAttempts,
    Duration retryWait
  )
  {
    static final Settings BUILT_IN = new Settings(
      Duration.ofSeconds(2),
      Duration.ofSeconds(5),
      50,
      20,
      10,
      Duration.ofSeconds(10),
      10,
      50,
      Duration.ofMillis(100),
      500,
      Duration.ofSeconds(1),
      3,
      Duration.ofMillis(200)
    );

    /** These settings, with anything not set taken from {@code other}. */
    Settings orElse(Settings other)
    {
      return new Settings(
        connectTimeout != null ? connectTimeout : other.connectTimeout,
        readTimeout != null ? readTimeout : other.readTimeout,
        failureRateThreshold != null ? failureRateThreshold : other.failureRateThreshold,
        slidingWindowSize != null ? slidingWindowSize : other.slidingWindowSize,
        minimumNumberOfCalls != null ? minimumNumberOfCalls : other.minimumNumberOfCalls,
        openStateWait != null ? openStateWait : other.openStateWait,
        halfOpenCalls != null ? halfOpenCalls : other.halfOpenCalls,
        maxConcurrentCalls != null ? maxConcurrentCalls : other.maxConcurrentCalls,
        bulkheadWait != null ? bulkheadWait : other.bulkheadWait,
        callsPerPeriod != null ? callsPerPeriod : other.callsPerPeriod,
        rateLimitPeriod != null ? rateLimitPeriod : other.rateLimitPeriod,
        maxAttempts != null ? maxAttempts : other.maxAttempts,
        retryWait != null ? retryWait : other.retryWait
      );
    }
  }
}
