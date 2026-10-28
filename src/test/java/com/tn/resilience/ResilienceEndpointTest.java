package com.tn.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import com.tn.resilience.ResilienceEndpoint.Circuit;
import com.tn.resilience.ResilienceEndpoint.Group;
import com.tn.resilience.ResilienceProperties.Settings;
import com.tn.resilience.ServiceUnavailableException.Reason;

class ResilienceEndpointTest
{
  private static final String GROUP = "tn-user-service";
  private static final byte[] NO_BODY = new byte[0];

  private final ResilienceGroups groups = new ResilienceGroups();
  private final ResilienceEndpoint endpoint = new ResilienceEndpoint(groups);

  @Test
  void shouldReportNoGroupsWhenServiceCallsNoOther()
  {
    assertTrue(endpoint.resilience().groups().isEmpty());
  }

  @Test
  void shouldReportGroupsInOrderCreated()
  {
    for (String group : List.of("tn-media-service", "tn-user-service", "okayat-spot-service")) interceptor(group, settings());

    assertEquals(List.of("tn-media-service", "tn-user-service", "okayat-spot-service"), List.copyOf(endpoint.resilience().groups().keySet()));
  }

  @Test
  void shouldReportClosedWithNoFailureRateBeforeEnoughCalls() throws IOException
  {
    ResilienceInterceptor interceptor = interceptor(GROUP, settings());
    assertNull(circuit().failureRate());

    call(interceptor, HttpStatus.OK);
    call(interceptor, HttpStatus.INTERNAL_SERVER_ERROR);

    Circuit circuit = circuit();
    assertEquals("CLOSED", circuit.state());
    assertNull(circuit.failureRate());
    assertEquals(2, circuit.bufferedCalls());
    assertEquals(1, circuit.failedCalls());
  }

  @Test
  void shouldReportCountsAndFailureRateOfCallsMade() throws IOException
  {
    ResilienceInterceptor interceptor = interceptor(GROUP, settings());

    for (int i = 0; i < 3; i++) call(interceptor, HttpStatus.OK);
    call(interceptor, HttpStatus.INTERNAL_SERVER_ERROR);

    Circuit circuit = circuit();
    assertEquals("CLOSED", circuit.state());
    assertEquals(25f, circuit.failureRate());
    assertEquals(4, circuit.bufferedCalls());
    assertEquals(1, circuit.failedCalls());
    assertEquals(0, circuit.notPermittedCalls());
  }

  @Test
  void shouldReportOpenWhenServiceHasBeenDown() throws IOException
  {
    ResilienceInterceptor interceptor = interceptor(GROUP, settings().withOpenStateWait(Duration.ofSeconds(30)));

    for (int i = 0; i < 4; i++) call(interceptor, HttpStatus.INTERNAL_SERVER_ERROR);
    ServiceUnavailableException e = assertThrows(ServiceUnavailableException.class, () -> call(interceptor, HttpStatus.OK));

    Circuit circuit = circuit();
    assertEquals(Reason.CIRCUIT_OPEN, e.reason());
    assertEquals("OPEN", circuit.state());
    assertEquals(100f, circuit.failureRate());
    assertEquals(4, circuit.failedCalls());
    assertEquals(1, circuit.notPermittedCalls());
  }

  @Test
  void shouldReportClosedAgainOnceServiceIsBackAndCircuitHasRested() throws Exception
  {
    ResilienceInterceptor interceptor = interceptor(GROUP, settings().withOpenStateWait(Duration.ofMillis(50)));

    for (int i = 0; i < 4; i++) call(interceptor, HttpStatus.INTERNAL_SERVER_ERROR);
    assertEquals("OPEN", circuit().state());
    Thread.sleep(100);

    // the first good call after the wait lets calls through to try the service; the circuit closes when the trial calls have all gone well
    call(interceptor, HttpStatus.OK);
    assertEquals("HALF_OPEN", circuit().state());
    for (int i = 0; i < 9; i++) call(interceptor, HttpStatus.OK);

    assertEquals("CLOSED", circuit().state());
  }

  @Test
  void shouldReportPlacesAndPermissionsLeft() throws IOException
  {
    ResilienceInterceptor interceptor = interceptor(GROUP, settings().withMaxConcurrentCalls(7).withCallsPerPeriod(10).withRateLimitPeriod(Duration.ofMinutes(1)));

    for (int i = 0; i < 3; i++) call(interceptor, HttpStatus.OK);

    Group group = endpoint.resilience().groups().get(GROUP);
    assertEquals(7, group.bulkhead().availableConcurrentCalls());
    assertEquals(7, group.bulkhead().maxAllowedConcurrentCalls());
    assertEquals(7, group.rateLimiter().availablePermissions());
  }

  @Test
  void shouldNotChangeAnythingWhenRead() throws IOException
  {
    ResilienceInterceptor interceptor = interceptor(GROUP, settings().withCallsPerPeriod(10).withRateLimitPeriod(Duration.ofMinutes(1)));
    call(interceptor, HttpStatus.OK);
    call(interceptor, HttpStatus.INTERNAL_SERVER_ERROR);
    Group before = endpoint.resilience().groups().get(GROUP);

    for (int i = 0; i < 100; i++) endpoint.resilience();

    assertEquals(before, endpoint.resilience().groups().get(GROUP));
  }

  private Circuit circuit()
  {
    return endpoint.resilience().groups().get(GROUP).circuitBreaker();
  }

  private ResilienceInterceptor interceptor(String group, TestSettings settings)
  {
    ResilienceInterceptor interceptor = new ResilienceInterceptor(group, settings.build());
    groups.add(interceptor);

    return interceptor;
  }

  private static ClientHttpResponse call(ResilienceInterceptor interceptor, HttpStatus answer) throws IOException
  {
    return interceptor.intercept(
      new MockClientHttpRequest(HttpMethod.GET, URI.create("http://localhost/v1/things")),
      NO_BODY,
      (request, body) -> new MockClientHttpResponse(NO_BODY, answer)
    );
  }

  /** Quick to trip, and never repeats: four failures out of four calls open the circuit. */
  private static TestSettings settings()
  {
    return new TestSettings(Settings.BUILT_IN)
      .withFailureRateThreshold(50)
      .withSlidingWindowSize(4)
      .withMinimumNumberOfCalls(4)
      .withMaxAttempts(1)
      .withMaxConcurrentCalls(50)
      .withCallsPerPeriod(10_000);
  }
}
