package com.tn.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.tn.resilience.ServiceUnavailableException.Reason;

class DownstreamErrorHandlerTest
{
  private final DownstreamErrorHandler handler = new DownstreamErrorHandler();

  @Test
  void shouldAnswerUnavailableWhenAServiceFails()
  {
    ResponseEntity<Void> response = handler.downstreamUnavailable(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom", HttpHeaders.EMPTY, new byte[0], null));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    assertNull(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
  }

  @Test
  void shouldAnswerUnavailableWhenAServiceCannotBeReached()
  {
    ResponseEntity<Void> response = handler.downstreamUnavailable(new ResourceAccessException("I/O error", new IOException("connection refused")));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
  }

  @Test
  void shouldSayWhenToTryAgainWhenTheCircuitIsOpen()
  {
    ServiceUnavailableException open = new ServiceUnavailableException("spot-service", Reason.CIRCUIT_OPEN, Duration.ofSeconds(10), null);

    ResponseEntity<Void> response = handler.downstreamUnavailable(new ResourceAccessException("I/O error", open));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    assertEquals("10", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
  }

  @Test
  void shouldRoundWaitUpToWholeSeconds()
  {
    ServiceUnavailableException limited = new ServiceUnavailableException("spot-service", Reason.RATE_LIMITED, Duration.ofMillis(200), null);

    assertEquals("1", handler.downstreamUnavailable(new ResourceAccessException("I/O error", limited)).getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
  }

  @Test
  void shouldAnswerUnavailableAndNeverTooManyRequestsWhenOurOwnLimitIsReached()
  {
    ServiceUnavailableException limited = new ServiceUnavailableException("profile-service", Reason.RATE_LIMITED, Duration.ofSeconds(1), null);

    ResponseEntity<Void> response = handler.downstreamUnavailable(new ResourceAccessException("I/O error", limited));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
  }

  @Test
  void shouldAnswerUnavailableWithoutAWaitWhenAllPlacesAreTaken()
  {
    ServiceUnavailableException full = new ServiceUnavailableException("spot-service", Reason.BULKHEAD_FULL, null, null);

    ResponseEntity<Void> response = handler.downstreamUnavailable(new ResourceAccessException("I/O error", full));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    assertNull(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
  }

  @Test
  void shouldPassOnAServicesOwnWaitWhenItIsUnavailable()
  {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "30");

    ResponseEntity<Void> response = handler.downstreamUnavailable(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "busy", headers, new byte[0], null));

    assertEquals("30", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
  }

  @Test
  void shouldPassOnAServicesTooManyRequestsAsTheRuleItIs()
  {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "86400");

    ResponseEntity<Void> response = handler.downstreamRejected(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "slow down", headers, new byte[0], null));

    assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
    assertEquals("86400", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
  }

  @Test
  void shouldPassOnAnyOtherClientErrorAsItIs()
  {
    ResponseEntity<Void> response = handler.downstreamRejected(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "no", HttpHeaders.EMPTY, new byte[0], null));

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }
}
