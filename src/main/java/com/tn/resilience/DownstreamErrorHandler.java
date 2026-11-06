package com.tn.resilience;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.time.Duration;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * What a service that calls other services answers when one of them lets it down: a downstream 4xx describes the caller's request, so it's
 * passed through as the same status; a downstream failure, a service that cannot be reached, or a call that the protection of calls between
 * services refused (its circuit is open, all the places for calls to it are taken, or this service has called it too often) is a service that
 * is unavailable just now, so it's a 503, with {@code Retry-After} where known. An app can show a 503 as a connection banner with Try again.
 *
 * <p>This service's own limit on calls to another is a 503 and never a 429: a 429 from a service is a rule about the member (following the
 * same member too often) and is passed on as it is, so the two must not be mistaken. A status that means something specific to one call is
 * translated where the call is made, before it gets here.
 *
 * <p>It is not registered by the library, as a service that calls others may answer their failures its own way: a service that wants it
 * declares it as a bean.
 */
@Slf4j
@RestControllerAdvice
public class DownstreamErrorHandler
{
  @ExceptionHandler(HttpClientErrorException.class)
  ResponseEntity<Void> downstreamRejected(HttpClientErrorException e)
  {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(e.getStatusCode());

    // how long to wait, for the limits downstream that say so
    HttpHeaders headers = e.getResponseHeaders();
    String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
    if (retryAfter != null) response.header(HttpHeaders.RETRY_AFTER, retryAfter);

    return response.build();
  }

  @ExceptionHandler({HttpServerErrorException.class, ResourceAccessException.class})
  ResponseEntity<Void> downstreamUnavailable(RuntimeException e)
  {
    log.warn("Downstream service unavailable", kv("reason", e.getMessage()));

    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
    retryAfter(e).ifPresent(wait -> response.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, wait.plusMillis(999).toSeconds()))));

    return response.build();
  }

  /** How long the protection of calls said to wait, or the service's own {@code Retry-After}. */
  private static Optional<Duration> retryAfter(RuntimeException e)
  {
    for (Throwable cause = e; cause != null; cause = cause.getCause())
    {
      if (cause instanceof ServiceUnavailableException unavailable && unavailable.retryAfter().isPresent()) return unavailable.retryAfter();
    }

    HttpHeaders headers = e instanceof HttpServerErrorException server ? server.getResponseHeaders() : null;
    if (headers != null)
    {
      String header = headers.getFirst(HttpHeaders.RETRY_AFTER);
      if (header != null)
      {
        try
        {
          return Optional.of(Duration.ofSeconds(Long.parseLong(header.trim())));
        }
        catch (NumberFormatException ignored)
        {
          // an HTTP date: the caller will try again when it likes
        }
      }
    }

    return Optional.empty();
  }
}
