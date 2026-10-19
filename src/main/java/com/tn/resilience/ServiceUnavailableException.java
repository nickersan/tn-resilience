package com.tn.resilience;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * A call to a service was not made, or gave up, because the service is not to be relied on just now: its circuit is open, all the
 * places for calls to it are taken, or too many calls have been made to it. It is an {@link IOException} so that the HTTP client
 * reports it as it does a service that cannot be reached, which a caller already handles.
 */
public class ServiceUnavailableException extends IOException
{
  public enum Reason
  {
    CIRCUIT_OPEN,
    BULKHEAD_FULL,
    RATE_LIMITED
  }

  private final String group;
  private final Reason reason;
  private final Duration retryAfter;

  public ServiceUnavailableException(String group, Reason reason, Duration retryAfter, Throwable cause)
  {
    super("Service " + group + " is unavailable: " + reason, cause);
    this.group = group;
    this.reason = reason;
    this.retryAfter = retryAfter;
  }

  public String group()
  {
    return group;
  }

  public Reason reason()
  {
    return reason;
  }

  /** How long to wait before trying again, when it is known. */
  public Optional<Duration> retryAfter()
  {
    return Optional.ofNullable(retryAfter);
  }
}
