package com.tn.resilience;

import java.time.Duration;

import com.tn.resilience.ResilienceProperties.Settings;

/** Settings for a test to change a little of, as {@link Settings} is a record with every setting a parameter. */
class TestSettings
{
  private Duration connectTimeout;
  private Duration readTimeout;
  private Integer failureRateThreshold;
  private Integer slidingWindowSize;
  private Integer minimumNumberOfCalls;
  private Duration openStateWait;
  private Integer maxConcurrentCalls;
  private Duration bulkheadWait;
  private Integer callsPerPeriod;
  private Duration rateLimitPeriod;
  private Integer maxAttempts;
  private Duration retryWait;

  TestSettings(Settings from)
  {
    connectTimeout = from.connectTimeout();
    readTimeout = from.readTimeout();
    failureRateThreshold = from.failureRateThreshold();
    slidingWindowSize = from.slidingWindowSize();
    minimumNumberOfCalls = from.minimumNumberOfCalls();
    openStateWait = from.openStateWait();
    maxConcurrentCalls = from.maxConcurrentCalls();
    bulkheadWait = from.bulkheadWait();
    callsPerPeriod = from.callsPerPeriod();
    rateLimitPeriod = from.rateLimitPeriod();
    maxAttempts = from.maxAttempts();
    retryWait = from.retryWait();
  }

  TestSettings withConnectTimeout(Duration connectTimeout)
  {
    this.connectTimeout = connectTimeout;
    return this;
  }

  TestSettings withReadTimeout(Duration readTimeout)
  {
    this.readTimeout = readTimeout;
    return this;
  }

  TestSettings withFailureRateThreshold(int failureRateThreshold)
  {
    this.failureRateThreshold = failureRateThreshold;
    return this;
  }

  TestSettings withSlidingWindowSize(int slidingWindowSize)
  {
    this.slidingWindowSize = slidingWindowSize;
    return this;
  }

  TestSettings withMinimumNumberOfCalls(int minimumNumberOfCalls)
  {
    this.minimumNumberOfCalls = minimumNumberOfCalls;
    return this;
  }

  TestSettings withOpenStateWait(Duration openStateWait)
  {
    this.openStateWait = openStateWait;
    return this;
  }

  TestSettings withMaxConcurrentCalls(int maxConcurrentCalls)
  {
    this.maxConcurrentCalls = maxConcurrentCalls;
    return this;
  }

  TestSettings withBulkheadWait(Duration bulkheadWait)
  {
    this.bulkheadWait = bulkheadWait;
    return this;
  }

  TestSettings withCallsPerPeriod(int callsPerPeriod)
  {
    this.callsPerPeriod = callsPerPeriod;
    return this;
  }

  TestSettings withRateLimitPeriod(Duration rateLimitPeriod)
  {
    this.rateLimitPeriod = rateLimitPeriod;
    return this;
  }

  TestSettings withMaxAttempts(int maxAttempts)
  {
    this.maxAttempts = maxAttempts;
    return this;
  }

  TestSettings withRetryWait(Duration retryWait)
  {
    this.retryWait = retryWait;
    return this;
  }

  Settings build()
  {
    return new Settings(
      connectTimeout,
      readTimeout,
      failureRateThreshold,
      slidingWindowSize,
      minimumNumberOfCalls,
      openStateWait,
      maxConcurrentCalls,
      bulkheadWait,
      callsPerPeriod,
      rateLimitPeriod,
      maxAttempts,
      retryWait
    );
  }
}
