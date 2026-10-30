# tn-resilience

Protects a service's calls to other services. Add the dependency and every `@HttpExchange` client registered through
`@ImportHttpServices` gets, per group (downstream service):

- connect and read **timeouts**;
- a **rate limiter**, a **bulkhead** and a **circuit breaker** (Resilience4j);
- **retries** for calls that are safe to repeat: `GET`, `HEAD`, `OPTIONS`, `PUT` and `DELETE`. `POST` and `PATCH` are never repeated.

A 5xx answer or an I/O failure counts against the service; any other answer, a 4xx included, does not. A call the protection refuses
fails with `ServiceUnavailableException` (an `IOException`, so the client reports it as `ResourceAccessException`, like a service that
cannot be reached) saying why and, when known, how long to wait. A service that is still answering 5xx on the last attempt is returned
as it answered, and the client raises its usual `HttpServerErrorException`.

```yaml
tn:
  resilience:
    enabled: true            # default
    defaults:                # every group starts from these; omitted settings use the built-in values
      read-timeout: 5s
    groups:
      spot-service:          # the @ImportHttpServices group name
        read-timeout: 10s
        max-concurrent-calls: 20
```

Settings: `connect-timeout`, `read-timeout`, `failure-rate-threshold`, `sliding-window-size`, `minimum-number-of-calls`,
`open-state-wait`, `half-open-calls`, `max-concurrent-calls`, `bulkhead-wait`, `calls-per-period`, `rate-limit-period`, `max-attempts`, `retry-wait`.

`half-open-calls` (default 10, Resilience4j's own) is how many calls an open circuit lets through to try the service once `open-state-wait`
has passed; it closes when they have all gone well. Set it low to recover quickly, e.g. `TN_RESILIENCE_DEFAULTS_HALFOPENCALLS=1`.

## Looking at the protection

With Actuator on the classpath the state of every group's protection is the read-only `resilience` endpoint
(`/actuator/resilience`, once exposed with `management.endpoints.web.exposure.include`). It is not part of the service's health, and
reading it never counts as a call. It is absent when `tn.resilience.enabled` is false.

It is read-only unless `management.endpoint.resilience.access=unrestricted` is set, which allows closing the circuit breakers: `POST /actuator/resilience`
closes every group's, `POST /actuator/resilience/{group}` one group's (a group that does not exist is a 400). A reset forgets the calls the breaker
had counted, and answers with the state afterwards, as the read does. It is for an environment that stops and starts a service on purpose (an
acceptance suite), where a caller's breaker should not have to learn by a call that the service is back; leave it off elsewhere.
Actuator only takes a body of JSON: `wget --header 'Content-Type: application/json' --post-data '{}' http://localhost:8080/actuator/resilience`.

```json
{
  "groups": {
    "tn-user-service": {
      "circuitBreaker": {"state": "CLOSED", "failureRate": null, "bufferedCalls": 0, "failedCalls": 0, "notPermittedCalls": 0},
      "bulkhead": {"availableConcurrentCalls": 50, "maxAllowedConcurrentCalls": 50},
      "rateLimiter": {"availablePermissions": 500}
    }
  }
}
```

- `state` is `CLOSED`, `OPEN`, `HALF_OPEN`, `DISABLED`, `FORCED_OPEN` or `METRICS_ONLY`. An open circuit shows as `OPEN` until the first call
  after `open-state-wait`, which moves it to `HALF_OPEN`; it closes once `half-open-calls` trial calls have gone well. State only moves when a
  call is made: nothing changes it while the service is idle, and reading the endpoint is not a call.
- `failureRate` is a percentage, `null` until `minimum-number-of-calls` have been seen.
- A service that calls no other answers `{"groups": {}}`. Groups are listed in the order they were created.
