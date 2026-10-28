package com.tn.resilience;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

import com.tn.resilience.ResilienceProperties.Settings;

/**
 * Protects the calls of every HTTP service group, each with its own settings: gives its client the timeouts, and the
 * {@link ResilienceInterceptor} that applies the rest.
 */
public class ResilienceConfigurer implements RestClientHttpServiceGroupConfigurer
{
  private final ResilienceProperties properties;
  private final ResilienceGroups groups;

  public ResilienceConfigurer(ResilienceProperties properties)
  {
    this(properties, new ResilienceGroups());
  }

  public ResilienceConfigurer(ResilienceProperties properties, ResilienceGroups groups)
  {
    this.properties = properties;
    this.groups = groups;
  }

  @Override
  public void configureGroups(Groups<RestClient.Builder> clients)
  {
    clients.forEachClient((group, builder) -> configure(group.name(), builder));
  }

  /** Protects the calls a client will make to the named group. */
  public void configure(String group, RestClient.Builder builder)
  {
    Settings settings = properties.forGroup(group);

    ResilienceInterceptor interceptor = new ResilienceInterceptor(group, settings);
    groups.add(interceptor);

    builder
      .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(HttpClientSettings.defaults().withTimeouts(settings.connectTimeout(), settings.readTimeout())))
      .requestInterceptor(interceptor);
  }
}
