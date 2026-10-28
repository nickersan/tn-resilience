package com.tn.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.HttpServiceGroupConfigurer.Groups;
import org.springframework.web.service.registry.HttpServiceGroupConfigurer.ClientCallback;
import tools.jackson.databind.json.JsonMapper;

import com.tn.resilience.ResilienceProperties.Settings;

class ResilienceEndpointIntegrationTest
{
  private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ResilienceAutoConfiguration.class));

  @Test
  void shouldHaveEndpointWithNoGroupsWhenServiceCallsNoOther()
  {
    runner.run(context ->
    {
      assertEquals(1, context.getBeansOfType(ResilienceEndpoint.class).size());
      assertTrue(context.getBean(ResilienceEndpoint.class).resilience().groups().isEmpty());
    });
  }

  @Test
  void shouldReportEveryGroupTheConfigurerProtects()
  {
    runner.run(context ->
    {
      ResilienceConfigurer configurer = context.getBean(ResilienceConfigurer.class);
      configurer.configure("tn-user-service", RestClient.builder());
      configurer.configure("tn-media-service", RestClient.builder());

      assertEquals(List.of("tn-user-service", "tn-media-service"), List.copyOf(context.getBean(ResilienceEndpoint.class).resilience().groups().keySet()));
    });
  }

  @Test
  void shouldProtectEveryClientOfEveryGroup()
  {
    ResilienceGroups groups = new ResilienceGroups();
    HttpServiceGroup group = mock(HttpServiceGroup.class);
    when(group.name()).thenReturn("tn-user-service");
    @SuppressWarnings("unchecked")
    Groups<RestClient.Builder> clients = mock(Groups.class);
    doAnswer(invocation ->
    {
      invocation.<ClientCallback<RestClient.Builder>>getArgument(0).withClient(group, RestClient.builder());
      return null;
    }).when(clients).forEachClient(any(ClientCallback.class));

    new ResilienceConfigurer(new ResilienceProperties(true, Settings.BUILT_IN, Map.of()), groups).configureGroups(clients);

    assertEquals(List.of("tn-user-service"), groups.interceptors().stream().map(ResilienceInterceptor::group).toList());
  }

  @Test
  void shouldNotHaveEndpointWhenDisabled()
  {
    runner.withPropertyValues("tn.resilience.enabled=false").run(context -> assertTrue(context.getBeansOfType(ResilienceEndpoint.class).isEmpty()));
  }

  @Test
  void shouldProtectCallsWithoutEndpointWhenActuatorIsNotOnClasspath()
  {
    runner.withClassLoader(new FilteredClassLoader(Endpoint.class)).run(context ->
    {
      assertTrue(context.getBeansOfType(ResilienceEndpoint.class).isEmpty());
      assertEquals(1, context.getBeansOfType(ResilienceConfigurer.class).size());
    });
  }

  @Test
  void shouldAnswerInTheAgreedShape()
  {
    ResilienceGroups groups = new ResilienceGroups();
    new ResilienceConfigurer(new ResilienceProperties(true, Settings.BUILT_IN, Map.of()), groups).configure("tn-user-service", RestClient.builder());

    String json = JsonMapper.builder().build().writeValueAsString(new ResilienceEndpoint(groups).resilience());

    assertEquals(
      "{\"groups\":{\"tn-user-service\":{"
        + "\"circuitBreaker\":{\"state\":\"CLOSED\",\"failureRate\":null,\"bufferedCalls\":0,\"failedCalls\":0,\"notPermittedCalls\":0},"
        + "\"bulkhead\":{\"availableConcurrentCalls\":50,\"maxAllowedConcurrentCalls\":50},"
        + "\"rateLimiter\":{\"availablePermissions\":500}}}}",
      json
    );
  }
}
