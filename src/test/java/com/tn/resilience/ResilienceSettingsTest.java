package com.tn.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.tn.resilience.ResilienceProperties.Settings;

class ResilienceSettingsTest
{
  private static final Settings NOTHING = new Settings(null, null, null, null, null, null, null, null, null, null, null, null, null);

  @Test
  void shouldTakeEverythingFromTheOtherWhenNothingIsSet()
  {
    assertEquals(Settings.BUILT_IN, NOTHING.orElse(Settings.BUILT_IN));
  }

  @Test
  void shouldKeepWhatIsSetAndTakeTheRestFromTheOther()
  {
    Settings set = new Settings(Duration.ofSeconds(9), null, 70, null, null, Duration.ofSeconds(30), null, null, null, 100, null, 5, null);

    Settings merged = set.orElse(Settings.BUILT_IN);

    assertEquals(Duration.ofSeconds(9), merged.connectTimeout());
    assertEquals(Settings.BUILT_IN.readTimeout(), merged.readTimeout());
    assertEquals(70, merged.failureRateThreshold());
    assertEquals(Settings.BUILT_IN.slidingWindowSize(), merged.slidingWindowSize());
    assertEquals(Settings.BUILT_IN.minimumNumberOfCalls(), merged.minimumNumberOfCalls());
    assertEquals(Duration.ofSeconds(30), merged.openStateWait());
    assertEquals(Settings.BUILT_IN.halfOpenCalls(), merged.halfOpenCalls());
    assertEquals(Settings.BUILT_IN.maxConcurrentCalls(), merged.maxConcurrentCalls());
    assertEquals(Settings.BUILT_IN.bulkheadWait(), merged.bulkheadWait());
    assertEquals(100, merged.callsPerPeriod());
    assertEquals(Settings.BUILT_IN.rateLimitPeriod(), merged.rateLimitPeriod());
    assertEquals(5, merged.maxAttempts());
    assertEquals(Settings.BUILT_IN.retryWait(), merged.retryWait());
  }

  @Test
  void shouldLeaveASettingNullWhenNeitherHasIt()
  {
    assertNull(NOTHING.orElse(NOTHING).connectTimeout());
    assertEquals(NOTHING, NOTHING.orElse(NOTHING));
  }
}
