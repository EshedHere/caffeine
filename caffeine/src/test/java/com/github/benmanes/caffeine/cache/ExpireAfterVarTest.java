/*
 * Copyright 2017 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_ACCESS;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_WRITE;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.VARIABLE;
import static com.github.benmanes.caffeine.cache.testing.RemovalListenerVerifier.verifyListeners;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.IsEmptyMap.emptyMap;
import static com.github.benmanes.caffeine.testing.IsFutureValue.futureOf;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.function.Function.identity;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mockito.Mockito;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Policy.VarExpiration;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.ImmutableList;

/**
 * The test cases for caches that support the variable expiration policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@SuppressWarnings("PreferJavaTimeOverload")
@Test(dataProviderClass = CacheProvider.class)
public final class ExpireAfterVarTest {

  @Test(dataProvider = "caches")
  @SuppressWarnings("deprecation")
  @CacheSpec(expiryTime = Expire.FOREVER,
      expiry = { CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS })
  public void expiry_bounds(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(System.nanoTime());
    var running = new AtomicBoolean();
    var done = new AtomicBoolean();
    Int key = context.absentKey();
    cache.put(key, key);

    try {
      ConcurrentTestHarness.execute(() -> {
        while (!done.get()) {
          context.ticker().advance(1, TimeUnit.MINUTES);
          cache.get(key, Int::new);
          running.set(true);
        }
      });
      await().untilTrue(running);
      cache.cleanUp();

      assertThat(cache.get(key, Int::new), sameInstance(key));
    } finally {
      done.set(true);
    }
  }

  /* --------------- Create --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE)
  public void put_replace(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);

    cache.put(context.firstKey(), context.absentValue());
    cache.put(context.absentKey(), context.absentValue());
    context.removalNotifications().clear(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(nullValue()));
    assertThat(cache.getIfPresent(context.middleKey()), is(nullValue()));
    assertThat(cache.getIfPresent(context.absentKey()), is(context.absentValue()));
    assertThat(cache.estimatedSize(), is(1L));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE)
  public void put_replace(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var future = context.absentValue().asFuture();
    context.ticker().advance(30, TimeUnit.SECONDS);

    cache.put(context.firstKey(), future);
    cache.put(context.absentKey(), future);
    context.removalNotifications().clear(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(nullValue()));
    assertThat(cache.getIfPresent(context.middleKey()), is(nullValue()));
    assertThat(cache.getIfPresent(context.absentKey()), is(futureOf(context.absentValue())));
    assertThat(cache.synchronous().estimatedSize(), is(1L));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE)
  public void put_replace(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);

    assertThat(map.put(context.firstKey(), context.absentValue()), is(not(nullValue())));
    assertThat(map.put(context.absentKey(), context.absentValue()), is(nullValue()));
    context.removalNotifications().clear(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(map.get(context.firstKey()), is(nullValue()));
    assertThat(map.get(context.middleKey()), is(nullValue()));
    assertThat(map.get(context.absentKey()), is(context.absentValue()));
    assertThat(map.size(), is(1));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE)
  public void putAll_replace(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);

    cache.putAll(Map.of(
        context.firstKey(), context.absentValue(),
        context.absentKey(), context.absentValue()));
    context.removalNotifications().clear(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(nullValue()));
    assertThat(cache.getIfPresent(context.middleKey()), is(nullValue()));
    assertThat(cache.getIfPresent(context.absentKey()), is(context.absentValue()));
    assertThat(cache.estimatedSize(), is(1L));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE)
  public void replace_updated(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.replace(context.firstKey(), context.absentValue()), is(not(nullValue())));
    context.ticker().advance(30, TimeUnit.SECONDS);

    context.cleanUp();
    assertThat(map.size(), is(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE)
  public void replaceConditionally_updated(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.replace(key, context.original().get(key), context.absentValue()), is(true));
    context.ticker().advance(30, TimeUnit.SECONDS);

    context.cleanUp();
    assertThat(map, is(emptyMap()));
  }

  /* --------------- Exceptional --------------- */

  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  @Test(dataProvider = "caches", expectedExceptions = ExpirationException.class)
  public void getIfPresent_expiryFails(Cache<Int, Int> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      when(context.expiry().expireAfterRead(any(), any(), anyLong(), anyLong()))
          .thenThrow(ExpirationException.class);
      cache.getIfPresent(context.firstKey());
    } finally {
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @SuppressWarnings("deprecation")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, removalListener = Listener.REJECTING)
  @Test(dataProvider = "caches", expectedExceptions = ExpirationException.class)
  public void get_expiryFails_create(Cache<Int, Int> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
          .thenThrow(ExpirationException.class);
      cache.get(context.absentKey(), identity());
    } finally {
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  @Test(dataProvider = "caches", expectedExceptions = ExpirationException.class)
  public void get_expiryFails_read(Cache<Int, Int> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      when(context.expiry().expireAfterRead(any(), any(), anyLong(), anyLong()))
          .thenThrow(ExpirationException.class);
      cache.get(context.firstKey(), identity());
    } finally {
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  @Test(dataProvider = "caches", expectedExceptions = ExpirationException.class)
  public void getAllPresent_expiryFails(Cache<Int, Int> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      when(context.expiry().expireAfterRead(any(), any(), anyLong(), anyLong()))
          .thenThrow(ExpirationException.class);
      cache.getAllPresent(context.firstMiddleLastKeys());
    } finally {
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  @Test(dataProvider = "caches", expectedExceptions = ExpirationException.class)
  public void put_insert_expiryFails(Cache<Int, Int> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
          .thenThrow(ExpirationException.class);
      cache.put(context.absentKey(), context.absentValue());
    } finally {
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @CacheSpec(implementation = Implementation.Caffeine, expiryTime = Expire.ONE_MINUTE,
      population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  @Test(dataProvider = "caches", expectedExceptions = ExpirationException.class)
  public void put_insert_replaceExpired_expiryFails(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireVariably) {
    var duration = expireVariably.getExpiresAfter(context.firstKey(), NANOSECONDS);
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
          .thenThrow(ExpirationException.class);
      cache.put(context.firstKey(), context.absentValue());
    } finally {
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
      assertThat(expireVariably.getExpiresAfter(context.firstKey(), NANOSECONDS), is(duration));
    }
  }

  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  @Test(dataProvider = "caches", expectedExceptions = ExpirationException.class)
  public void put_update_expiryFails(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireVariably) {
    var duration = expireVariably.getExpiresAfter(context.firstKey(), NANOSECONDS);
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
          .thenThrow(ExpirationException.class);
      cache.put(context.firstKey(), context.absentValue());
    } finally {
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
      assertThat(expireVariably.getExpiresAfter(context.firstKey(), NANOSECONDS), is(duration));
    }
  }

  static final class ExpirationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  /* --------------- Policy --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS })
  public void getIfPresentQuietly(Cache<Int, Int> cache, CacheContext context) {
    var original = cache.policy().expireVariably().orElseThrow()
        .getExpiresAfter(context.firstKey()).orElseThrow();
    var advancement = Duration.ofSeconds(30);
    context.ticker().advance(advancement);
    cache.policy().getIfPresentQuietly(context.firstKey());
    var current = cache.policy().expireVariably().orElseThrow()
        .getExpiresAfter(context.firstKey()).orElseThrow();
    assertThat(current.plus(advancement), is(original));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.EMPTY, expiry = CacheExpiry.DISABLED)
  public void expireVariably_notEnabled(Cache<Int, Int> cache) {
    assertThat(cache.policy().expireVariably(), is(Optional.empty()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void getExpiresAfter(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.MINUTES),
        is(OptionalLong.empty()));
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey(), TimeUnit.MINUTES),
        is(OptionalLong.of(1)));

    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(TimeUnit.HOURS.toNanos(1));
    cache.put(context.firstKey(), context.absentValue());
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey(), TimeUnit.MINUTES),
        is(OptionalLong.of(60)));

    assertThat(expireAfterVar.getExpiresAfter(context.lastKey(), TimeUnit.MINUTES),
        is(OptionalLong.of(1)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void getExpiresAfter_duration(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey()),
        is(Optional.empty()));
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey()),
        is(Optional.of(Duration.ofMinutes(1L))));

    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(TimeUnit.HOURS.toNanos(1));
    cache.put(context.firstKey(), context.absentValue());
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey()),
        is(Optional.of(Duration.ofHours(1))));

    assertThat(expireAfterVar.getExpiresAfter(context.lastKey()),
        is(Optional.of(Duration.ofMinutes(1))));
  }

  @SuppressWarnings("unchecked")
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void getExpiresAfter_absent(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    Mockito.reset(context.expiry());
    assertThat(expireAfterVar.getExpiresAfter(
        context.absentKey(), TimeUnit.SECONDS).isPresent(), is(false));
    verifyNoInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE }, expiryTime = Expire.ONE_MINUTE,
      population = Population.EMPTY)
  public void getExpiresAfter_expired(Cache<Int, Int> cache, CacheContext context,
      VarExpiration<Int, Int> expireAfterVar) {
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(2, TimeUnit.MINUTES);
    assertThat(expireAfterVar.getExpiresAfter(
        context.absentKey(), TimeUnit.SECONDS).isPresent(), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void setExpiresAfter(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.setExpiresAfter(context.firstKey(), 2, TimeUnit.MINUTES);
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey(), TimeUnit.MINUTES),
        is(OptionalLong.of(2)));

    expireAfterVar.setExpiresAfter(context.absentKey(), 4, TimeUnit.MINUTES);
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.MINUTES),
        is(OptionalLong.empty()));

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void setExpiresAfter_duration(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.setExpiresAfter(context.firstKey(), Duration.ofMinutes(2L));
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey()),
        is(Optional.of(Duration.ofMinutes(2L))));

    expireAfterVar.setExpiresAfter(context.absentKey(), Duration.ofMinutes(4L));
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey()), is(Optional.empty()));

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(1L));
  }

  @SuppressWarnings("unchecked")
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void setExpiresAfter_absent(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.setExpiresAfter(context.absentKey(), 1, TimeUnit.SECONDS);
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.asMap(), is(equalTo(context.original())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE }, expiryTime = Expire.ONE_MINUTE,
      population = Population.EMPTY)
  public void setExpiresAfter_expired(Cache<Int, Int> cache, CacheContext context,
      VarExpiration<Int, Int> expireAfterVar) {
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(2, TimeUnit.MINUTES);
    expireAfterVar.setExpiresAfter(context.absentKey(), 1, TimeUnit.MINUTES);
    cache.cleanUp();
    assertThat(cache.asMap(), is(anEmptyMap()));
  }

  /* --------------- Policy: putIfAbsent --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent_nullKey(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.putIfAbsent(null, Int.valueOf(2), 3, TimeUnit.SECONDS);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent_nullValue(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.putIfAbsent(Int.valueOf(1), null, 3, TimeUnit.SECONDS);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent_nullTimeUnit(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.putIfAbsent(Int.valueOf(1), Int.valueOf(2), 3, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent_negativeDuration(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.putIfAbsent(Int.valueOf(1), Int.valueOf(2), -10, TimeUnit.SECONDS);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent_nullDuration(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.putIfAbsent(Int.valueOf(1), Int.valueOf(2), null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent_insert(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    Int key = context.absentKey();
    Int value = context.absentValue();
    Int result = expireAfterVar.putIfAbsent(key, value, Duration.ofMinutes(2L));
    assertThat(result, is(nullValue()));

    assertThat(cache.getIfPresent(key), is(value));
    assertThat(expireAfterVar.getExpiresAfter(key), is(Optional.of(Duration.ofMinutes(2L))));

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent_present(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    Int key = context.firstKey();
    Int value = context.absentValue();
    Int result = expireAfterVar.putIfAbsent(key, value, Duration.ofMinutes(2L));
    assertThat(result, is(context.original().get(key)));

    assertThat(cache.getIfPresent(key), is(context.original().get(key)));
    assertThat(expireAfterVar.getExpiresAfter(key), is(Optional.of(Duration.ofMinutes(1L))));

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(0L));
  }

  /* --------------- Policy: put --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void put_nullKey(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.put(null, Int.valueOf(2), 3, TimeUnit.SECONDS);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void put_nullValue(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.put(Int.valueOf(1), null, 3, TimeUnit.SECONDS);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void put_nullTimeUnit(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.put(Int.valueOf(1), Int.valueOf(2), 3, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void put_negativeDuration(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.put(Int.valueOf(1), Int.valueOf(2), -10, TimeUnit.SECONDS);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void put_nullDuration(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.put(Int.valueOf(1), Int.valueOf(2), null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void put_insert(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    Int key = context.absentKey();
    Int value = context.absentValue();
    Int oldValue = expireAfterVar.put(key, value, Duration.ofMinutes(2L));
    assertThat(oldValue, is(nullValue()));

    assertThat(cache.getIfPresent(key), is(value));
    assertThat(expireAfterVar.getExpiresAfter(key), is(Optional.of(Duration.ofMinutes(2L))));

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void put_replace(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    Int key = context.firstKey();
    Int value = context.absentValue();
    Int oldValue = expireAfterVar.put(key, value, Duration.ofMinutes(2L));
    assertThat(oldValue, is(context.original().get(key)));

    assertThat(cache.getIfPresent(key), is(value));
    assertThat(expireAfterVar.getExpiresAfter(key), is(Optional.of(Duration.ofMinutes(2L))));

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(1L));
  }

  /* --------------- Policy: oldest --------------- */

  @CacheSpec(implementation = Implementation.Caffeine, expiry = CacheExpiry.ACCESS)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void oldest_unmodifiable(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.oldest(Integer.MAX_VALUE).clear();
  }

  @CacheSpec(implementation = Implementation.Caffeine, expiry = CacheExpiry.ACCESS)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void oldest_negative(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.oldest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expiry = CacheExpiry.ACCESS)
  public void oldest_zero(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThat(expireAfterVar.oldest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expiry = CacheExpiry.ACCESS)
  public void oldest_partial(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    int count = (int) context.initialSize() / 2;
    assertThat(expireAfterVar.oldest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = {Population.PARTIAL, Population.FULL}, expiry = CacheExpiry.ACCESS,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void oldest_order(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var oldest = expireAfterVar.oldest(Integer.MAX_VALUE);
    assertThat(oldest.keySet(), contains(context.original().keySet().toArray(new Int[0])));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expiry = CacheExpiry.ACCESS)
  public void oldest_snapshot(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var oldest = expireAfterVar.oldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(oldest, is(equalTo(context.original())));
  }

  /* --------------- Policy: youngest --------------- */

  @CacheSpec(implementation = Implementation.Caffeine, expiry = CacheExpiry.ACCESS)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void youngest_unmodifiable(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.youngest(Integer.MAX_VALUE).clear();
  }

  @CacheSpec(implementation = Implementation.Caffeine, expiry = CacheExpiry.ACCESS)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void youngest_negative(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.youngest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expiry = CacheExpiry.ACCESS)
  public void youngest_zero(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThat(expireAfterVar.youngest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expiry = CacheExpiry.ACCESS)
  public void youngest_partial(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    int count = (int) context.initialSize() / 2;
    assertThat(expireAfterVar.youngest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = {Population.PARTIAL, Population.FULL}, expiry = CacheExpiry.ACCESS,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void youngest_order(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var youngest = expireAfterVar.youngest(Integer.MAX_VALUE);
    var keys = new LinkedHashSet<>(ImmutableList.copyOf(youngest.keySet()).reverse());
    assertThat(keys, contains(keys.toArray(Int[]::new)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expiry = CacheExpiry.ACCESS)
  public void youngest_snapshot(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var youngest = expireAfterVar.youngest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(youngest, is(equalTo(context.original())));
  }
}
