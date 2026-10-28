package com.tn.resilience;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

/**
 * Protects every call made through an HTTP service group ({@code @ImportHttpServices}) once the library is a dependency, unless
 * {@code tn.resilience.enabled} is false.
 */
@AutoConfiguration
@ConditionalOnClass(RestClientHttpServiceGroupConfigurer.class)
@ConditionalOnProperty(prefix = "tn.resilience", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(ResilienceProperties.class)
public class ResilienceAutoConfiguration
{
  @Bean
  ResilienceGroups resilienceGroups()
  {
    return new ResilienceGroups();
  }

  @Bean
  ResilienceConfigurer resilienceConfigurer(ResilienceProperties properties, ResilienceGroups groups)
  {
    return new ResilienceConfigurer(properties, groups);
  }

  /** Lets the state of the protection be read as the {@code resilience} endpoint, where Actuator is on the classpath. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(Endpoint.class)
  static class EndpointConfiguration
  {
    @Bean
    ResilienceEndpoint resilienceEndpoint(ResilienceGroups groups)
    {
      return new ResilienceEndpoint(groups);
    }
  }
}
