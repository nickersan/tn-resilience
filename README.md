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
`open-state-wait`, `max-concurrent-calls`, `bulkhead-wait`, `calls-per-period`, `rate-limit-period`, `max-attempts`, `retry-wait`.
