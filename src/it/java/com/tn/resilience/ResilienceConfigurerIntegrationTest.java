package com.tn.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.tn.resilience.ResilienceProperties.Settings;

class ResilienceConfigurerIntegrationTest
{
  private static final String GROUP = "spot-service";

  private final AtomicInteger failingCalls = new AtomicInteger();

  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException
  {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);

    server.createContext("/ok", exchange ->
    {
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
    });

    server.createContext("/slow", exchange ->
    {
      try
      {
        Thread.sleep(1500);
        exchange.sendResponseHeaders(204, -1);
      }
      catch (InterruptedException | IOException e)
      {
        // the client gave up
      }
      finally
      {
        exchange.close();
      }
    });

    server.createContext("/failing", exchange ->
    {
      failingCalls.incrementAndGet();
      exchange.sendResponseHeaders(500, -1);
      exchange.close();
    });

    server.start();
  }

  @AfterEach
  void stopServer()
  {
    server.stop(0);
  }

  @Test
  void shouldAnswerWhenServiceAnswers()
  {
    client(settings(Duration.ofSeconds(1))).get().uri("/ok").retrieve().toBodilessEntity();
  }

  @Test
  void shouldGiveUpOnServiceThatIsTooSlow()
  {
    RestClient client = client(settings(Duration.ofMillis(200)));

    long started = System.nanoTime();
    ResourceAccessException e = assertThrows(ResourceAccessException.class, () -> client.get().uri("/slow").retrieve().toBodilessEntity());
    Duration took = Duration.ofNanos(System.nanoTime() - started);

    assertInstanceOf(IOException.class, e.getCause());
    assertTrue(took.compareTo(Duration.ofMillis(1400)) < 0, "gave up after " + took);
  }

  @Test
  void shouldRepeatReadAndThenReportServerErrorWhenServiceKeepsFailing()
  {
    RestClient client = client(settings(Duration.ofSeconds(1)));

    assertThrows(HttpServerErrorException.class, () -> client.get().uri("/failing").retrieve().toBodilessEntity());

    assertEquals(3, failingCalls.get());
  }

  @Test
  void shouldNotRepeatCreateWhenServiceFails()
  {
    RestClient client = client(settings(Duration.ofSeconds(1)));

    assertThrows(HttpServerErrorException.class, () -> client.post().uri("/failing").retrieve().toBodilessEntity());

    assertEquals(1, failingCalls.get());
  }

  @Test
  void shouldProtectGroupsWhenConfigured()
  {
    new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ResilienceAutoConfiguration.class))
      .withPropertyValues("tn.resilience.groups." + GROUP + ".read-timeout=30s", "tn.resilience.defaults.max-attempts=5")
      .run(context ->
      {
        assertTrue(context.containsBean("resilienceConfigurer"));
        ResilienceProperties properties = context.getBean(ResilienceProperties.class);
        assertEquals(Duration.ofSeconds(30), properties.forGroup(GROUP).readTimeout());
        assertEquals(5, properties.forGroup(GROUP).maxAttempts());
        assertEquals(Settings.BUILT_IN.connectTimeout(), properties.forGroup(GROUP).connectTimeout());
      });
  }

  @Test
  void shouldBindHalfOpenCallsFromConfiguration()
  {
    new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ResilienceAutoConfiguration.class))
      .withPropertyValues("tn.resilience.defaults.half-open-calls=1", "tn.resilience.groups." + GROUP + ".half-open-calls=3")
      .run(context ->
      {
        ResilienceProperties properties = context.getBean(ResilienceProperties.class);
        assertEquals(3, properties.forGroup(GROUP).halfOpenCalls());
        assertEquals(1, properties.forGroup("another").halfOpenCalls());
      });
  }

  @Test
  void shouldNotProtectGroupsWhenDisabled()
  {
    new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ResilienceAutoConfiguration.class))
      .withPropertyValues("tn.resilience.enabled=false")
      .run(context -> assertTrue(context.getBeansOfType(ResilienceConfigurer.class).isEmpty()));
  }

  private RestClient client(Settings settings)
  {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:" + server.getAddress().getPort());
    new ResilienceConfigurer(new ResilienceProperties(true, settings, Map.of())).configure(GROUP, builder);

    return builder.build();
  }

  private static Settings settings(Duration readTimeout)
  {
    return new TestSettings(Settings.BUILT_IN).withReadTimeout(readTimeout).withRetryWait(Duration.ofMillis(1)).build();
  }
}
