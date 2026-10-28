package com.tn.resilience;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The protection of every group the service calls, in the order each was created, so that it can be looked at while it works. It
 * holds what {@link ResilienceConfigurer} built; it builds nothing and changes nothing.
 */
public class ResilienceGroups
{
  private final List<ResilienceInterceptor> interceptors = new CopyOnWriteArrayList<>();

  void add(ResilienceInterceptor interceptor)
  {
    interceptors.add(interceptor);
  }

  /** The protection of each group, in the order the groups were created. */
  public List<ResilienceInterceptor> interceptors()
  {
    return List.copyOf(interceptors);
  }
}
