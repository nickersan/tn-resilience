package com.tn.resilience;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
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
  ResilienceConfigurer resilienceConfigurer(ResilienceProperties properties)
  {
    return new ResilienceConfigurer(properties);
  }
}
