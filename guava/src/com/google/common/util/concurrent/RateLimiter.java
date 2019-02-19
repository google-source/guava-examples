/*
 * Copyright (C) 2012 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.util.concurrent;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Math.max;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.annotations.Beta;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.SmoothRateLimiter.SmoothBursty;
import com.google.common.util.concurrent.SmoothRateLimiter.SmoothWarmingUp;

import java.util.concurrent.TimeUnit;

import javax.annotation.concurrent.ThreadSafe;

/**
 * 速率限制器。
 * 从概念上讲，速率限制器以可配置的速率分配许可。
 * 每个{@link #acquire()}都会在必要时阻塞，直到有许可证为止，然后接受它。
 * 获得后，不需要发放许可证。
 *
 * 速率限制器通常用于限制访问某些物理或逻辑资源的速率。
 * 这与{@link java.util.concurrent.Semaphore}相反，{@link java.util.concurrent.Semaphore}限制并发访问的数量而不是速率
 * （注意并发和速率密切相关，例如参见<a href =“http//en.wikipedia.org/wiki/Little's_law“>Little's Law</a>
 *
 * {@code RateLimiter}主要由颁发许可证的费率定义。
 * 如果没有其他配置，许可将以固定费率分配，以每秒许可数定义。
 * 许可证将顺利分配，个别许可证之间的延迟将被调整，以确保维持配置的费率。
 *
 * 可以将{@code RateLimiter}配置为具有预热期，在此期间每秒发出的许可稳定地增加，直到达到稳定速率。
 *
 * 例如，假设我们有一个要执行的任务列表，但我们不希望每秒提交超过2个:
 *<pre>  {@code
 *  final RateLimiter rateLimiter = RateLimiter.create(2.0); // rate is "2 permits per second"
 *  void submitTasks(List<Runnable> tasks, Executor executor) {
 *    for (Runnable task : tasks) {
 *      rateLimiter.acquire(); // may wait
 *      executor.execute(task);
 *    }
 *  }
 *}</pre>
 *
 * <p>再举一个例子，假设我们生成了一个数据流，我们希望以每秒5kb的速度限制它。 这可以通过每字节要求许可，并指定每秒5000许可的速率来实现:
 *<pre>  {@code
 *  final RateLimiter rateLimiter = RateLimiter.create(5000.0); // rate = 5000 permits per second
 *  void submitPacket(byte[] packet) {
 *    rateLimiter.acquire(packet.length);
 *    networkService.send(packet);
 *  }
 *}</pre>
 *
 * 重要的是要注意请求的许可数<i>从不</ i>影响请求本身的限制（对{@code acquire（1）}的调用和对{@code acquire（1000）}的调用 将导致完全相同的限制（如果有的话），但它会影响<i> next </ i>请求的限制。 
 * 即，如果昂贵的任务到达空闲的RateLimiter，它将立即被授予，但是<i> next </ i>请求将经历额外的限制，从而支付昂贵任务的成本。
 *
 * 注意：{@ code RateLimiter}不提供公平性保证.
 *
 * @author Dimitris Andreou
 * @since 13.0
 */
// TODO(user): switch to nano precision. A natural unit of cost is "bytes", and a micro precision
//     would mean a maximum rate of "1MB/s", which might be small in some cases.
@ThreadSafe
@Beta
public abstract class RateLimiter {
  /**
   * Creates a {@code RateLimiter} with the specified stable throughput, given as
   * "permits per second" (commonly referred to as <i>QPS</i>, queries per second).
   *
   * <p>The returned {@code RateLimiter} ensures that on average no more than {@code
   * permitsPerSecond} are issued during any given second, with sustained requests
   * being smoothly spread over each second. When the incoming request rate exceeds
   * {@code permitsPerSecond} the rate limiter will release one permit every {@code
   * (1.0 / permitsPerSecond)} seconds. When the rate limiter is unused,
   * bursts of up to {@code permitsPerSecond} permits will be allowed, with subsequent
   * requests being smoothly limited at the stable rate of {@code permitsPerSecond}.
   *
   * @param permitsPerSecond the rate of the returned {@code RateLimiter}, measured in
   *        how many permits become available per second
   * @throws IllegalArgumentException if {@code permitsPerSecond} is negative or zero
   */
  // TODO(user): "This is equivalent to
  //                 {@code createWithCapacity(permitsPerSecond, 1, TimeUnit.SECONDS)}".
  public static RateLimiter create(double permitsPerSecond) {
    /*
     * The default RateLimiter configuration can save the unused permits of up to one second.
     * This is to avoid unnecessary stalls in situations like this: A RateLimiter of 1qps,
     * and 4 threads, all calling acquire() at these moments:
     *
     * T0 at 0 seconds
     * T1 at 1.05 seconds
     * T2 at 2 seconds
     * T3 at 3 seconds
     *
     * Due to the slight delay of T1, T2 would have to sleep till 2.05 seconds,
     * and T3 would also have to sleep till 3.05 seconds.
     */
    return create(SleepingStopwatch.createFromSystemTimer(), permitsPerSecond);
  }

  /*
   * TODO(cpovirk): make SleepingStopwatch the last parameter throughout the class so that the
   * overloads follow the usual convention: Foo(int), Foo(int, SleepingStopwatch)
   */
  @VisibleForTesting
  static RateLimiter create(SleepingStopwatch stopwatch, double permitsPerSecond) {
    RateLimiter rateLimiter = new SmoothBursty(stopwatch, 1.0 /* maxBurstSeconds */);
    rateLimiter.setRate(permitsPerSecond);
    return rateLimiter;
  }

  /**
   * Creates a {@code RateLimiter} with the specified stable throughput, given as
   * "permits per second" (commonly referred to as <i>QPS</i>, queries per second), and a
   * <i>warmup period</i>, during which the {@code RateLimiter} smoothly ramps up its rate,
   * until it reaches its maximum rate at the end of the period (as long as there are enough
   * requests to saturate it). Similarly, if the {@code RateLimiter} is left <i>unused</i> for
   * a duration of {@code warmupPeriod}, it will gradually return to its "cold" state,
   * i.e. it will go through the same warming up process as when it was first created.
   *
   * <p>The returned {@code RateLimiter} is intended for cases where the resource that actually
   * fulfills the requests (e.g., a remote server) needs "warmup" time, rather than
   * being immediately accessed at the stable (maximum) rate.
   *
   * <p>The returned {@code RateLimiter} starts in a "cold" state (i.e. the warmup period
   * will follow), and if it is left unused for long enough, it will return to that state.
   *
   * @param permitsPerSecond the rate of the returned {@code RateLimiter}, measured in
   *        how many permits become available per second
   * @param warmupPeriod the duration of the period where the {@code RateLimiter} ramps up its
   *        rate, before reaching its stable (maximum) rate
   * @param unit the time unit of the warmupPeriod argument
   * @throws IllegalArgumentException if {@code permitsPerSecond} is negative or zero or
   *     {@code warmupPeriod} is negative
   */
  public static RateLimiter create(double permitsPerSecond, long warmupPeriod, TimeUnit unit) {
    checkArgument(warmupPeriod >= 0, "warmupPeriod must not be negative: %s", warmupPeriod);
    return create(SleepingStopwatch.createFromSystemTimer(), permitsPerSecond, warmupPeriod, unit);
  }

  @VisibleForTesting
  static RateLimiter create(
      SleepingStopwatch stopwatch, double permitsPerSecond, long warmupPeriod, TimeUnit unit) {
    RateLimiter rateLimiter = new SmoothWarmingUp(stopwatch, warmupPeriod, unit);
    rateLimiter.setRate(permitsPerSecond);
    return rateLimiter;
  }

  /**
   * The underlying timer; used both to measure elapsed time and sleep as necessary. A separate
   * object to facilitate testing.
   */
  private final SleepingStopwatch stopwatch;

  // Can't be initialized in the constructor because mocks don't call the constructor.
  private volatile Object mutexDoNotUseDirectly;

  private Object mutex() {
    Object mutex = mutexDoNotUseDirectly;
    if (mutex == null) {
      synchronized (this) {
        mutex = mutexDoNotUseDirectly;
        if (mutex == null) {
          mutexDoNotUseDirectly = mutex = new Object();
        }
      }
    }
    return mutex;
  }

  RateLimiter(SleepingStopwatch stopwatch) {
    this.stopwatch = checkNotNull(stopwatch);
  }

  /**
   * Updates the stable rate of this {@code RateLimiter}, that is, the
   * {@code permitsPerSecond} argument provided in the factory method that
   * constructed the {@code RateLimiter}. Currently throttled threads will <b>not</b>
   * be awakened as a result of this invocation, thus they do not observe the new rate;
   * only subsequent requests will.
   *
   * <p>Note though that, since each request repays (by waiting, if necessary) the cost
   * of the <i>previous</i> request, this means that the very next request
   * after an invocation to {@code setRate} will not be affected by the new rate;
   * it will pay the cost of the previous request, which is in terms of the previous rate.
   *
   * <p>The behavior of the {@code RateLimiter} is not modified in any other way,
   * e.g. if the {@code RateLimiter} was configured with a warmup period of 20 seconds,
   * it still has a warmup period of 20 seconds after this method invocation.
   *
   * @param permitsPerSecond the new stable rate of this {@code RateLimiter}
   * @throws IllegalArgumentException if {@code permitsPerSecond} is negative or zero
   */
  public final void setRate(double permitsPerSecond) {
    checkArgument(
        permitsPerSecond > 0.0 && !Double.isNaN(permitsPerSecond), "rate must be positive");
    synchronized (mutex()) {
      doSetRate(permitsPerSecond, stopwatch.readMicros());
    }
  }

  abstract void doSetRate(double permitsPerSecond, long nowMicros);

  /**
   * Returns the stable rate (as {@code permits per seconds}) with which this
   * {@code RateLimiter} is configured with. The initial value of this is the same as
   * the {@code permitsPerSecond} argument passed in the factory method that produced
   * this {@code RateLimiter}, and it is only updated after invocations
   * to {@linkplain #setRate}.
   */
  public final double getRate() {
    synchronized (mutex()) {
      return doGetRate();
    }
  }

  abstract double doGetRate();

  /**
   * Acquires a single permit from this {@code RateLimiter}, blocking until the
   * request can be granted. Tells the amount of time slept, if any.
   *
   * <p>This method is equivalent to {@code acquire(1)}.
   *
   * @return time spent sleeping to enforce rate, in seconds; 0.0 if not rate-limited
   * @since 16.0 (present in 13.0 with {@code void} return type})
   */
  public double acquire() {
    return acquire(1);
  }

  /**
   * Acquires the given number of permits from this {@code RateLimiter}, blocking until the
   * request can be granted. Tells the amount of time slept, if any.
   *
   * @param permits the number of permits to acquire
   * @return time spent sleeping to enforce rate, in seconds; 0.0 if not rate-limited
   * @throws IllegalArgumentException if the requested number of permits is negative or zero
   * @since 16.0 (present in 13.0 with {@code void} return type})
   */
  public double acquire(int permits) {
    long microsToWait = reserve(permits);
    stopwatch.sleepMicrosUninterruptibly(microsToWait);
    return 1.0 * microsToWait / SECONDS.toMicros(1L);
  }

  /**
   * Reserves the given number of permits from this {@code RateLimiter} for future use, returning
   * the number of microseconds until the reservation can be consumed.
   *
   * @return time in microseconds to wait until the resource can be acquired, never negative
   */
  final long reserve(int permits) {
    checkPermits(permits);
    synchronized (mutex()) {
      return reserveAndGetWaitLength(permits, stopwatch.readMicros());
    }
  }

  /**
   * Acquires a permit from this {@code RateLimiter} if it can be obtained
   * without exceeding the specified {@code timeout}, or returns {@code false}
   * immediately (without waiting) if the permit would not have been granted
   * before the timeout expired.
   *
   * <p>This method is equivalent to {@code tryAcquire(1, timeout, unit)}.
   *
   * @param timeout the maximum time to wait for the permit. Negative values are treated as zero.
   * @param unit the time unit of the timeout argument
   * @return {@code true} if the permit was acquired, {@code false} otherwise
   * @throws IllegalArgumentException if the requested number of permits is negative or zero
   */
  public boolean tryAcquire(long timeout, TimeUnit unit) {
    return tryAcquire(1, timeout, unit);
  }

  /**
   * Acquires permits from this {@link RateLimiter} if it can be acquired immediately without delay.
   *
   * <p>
   * This method is equivalent to {@code tryAcquire(permits, 0, anyUnit)}.
   *
   * @param permits the number of permits to acquire
   * @return {@code true} if the permits were acquired, {@code false} otherwise
   * @throws IllegalArgumentException if the requested number of permits is negative or zero
   * @since 14.0
   */
  public boolean tryAcquire(int permits) {
    return tryAcquire(permits, 0, MICROSECONDS);
  }

  /**
   * Acquires a permit from this {@link RateLimiter} if it can be acquired immediately without
   * delay.
   *
   * <p>
   * This method is equivalent to {@code tryAcquire(1)}.
   *
   * @return {@code true} if the permit was acquired, {@code false} otherwise
   * @since 14.0
   */
  public boolean tryAcquire() {
    return tryAcquire(1, 0, MICROSECONDS);
  }

  /**
   * Acquires the given number of permits from this {@code RateLimiter} if it can be obtained
   * without exceeding the specified {@code timeout}, or returns {@code false}
   * immediately (without waiting) if the permits would not have been granted
   * before the timeout expired.
   *
   * @param permits the number of permits to acquire
   * @param timeout the maximum time to wait for the permits. Negative values are treated as zero.
   * @param unit the time unit of the timeout argument
   * @return {@code true} if the permits were acquired, {@code false} otherwise
   * @throws IllegalArgumentException if the requested number of permits is negative or zero
   */
  public boolean tryAcquire(int permits, long timeout, TimeUnit unit) {
	// 获取指定时间范围的毫秒数
    long timeoutMicros = max(unit.toMicros(timeout), 0);
    checkPermits(permits);
    long microsToWait;
    synchronized (mutex()) {
      // 获取当前的毫秒时间
      long nowMicros = stopwatch.readMicros();
      if (!canAcquire(nowMicros, timeoutMicros)) {
        return false;
      } else {
    	// 成功获取令牌需要等待的时间
        microsToWait = reserveAndGetWaitLength(permits, nowMicros);
      }
    }
    // 休眠需要等待的时间,阻塞获取令牌
    stopwatch.sleepMicrosUninterruptibly(microsToWait);
    return true;
  }

  private boolean canAcquire(long nowMicros, long timeoutMicros) {
	// 下一个令牌获取需要等待的时间毫秒减去指定等待的时间毫秒是否小于等于当前时间毫秒, 表明在可以获取的时间范围内
    return queryEarliestAvailable(nowMicros) - timeoutMicros <= nowMicros;
  }

  /**
   * Reserves next ticket and returns the wait time that the caller must wait for.
   *
   * @return the required wait time, never negative
   */
  final long reserveAndGetWaitLength(int permits, long nowMicros) {
	// 获取下一个令牌需要等待的时间毫秒
    long momentAvailable = reserveEarliestAvailable(permits, nowMicros);
    // 减去当前时间毫秒值,为需要等待的时间
    return max(momentAvailable - nowMicros, 0);
  }

  /**
   * Returns the earliest time that permits are available (with one caveat).
   *
   * @return the time that permits are available, or, if permits are available immediately, an
   *     arbitrary past or present time
   */
  abstract long queryEarliestAvailable(long nowMicros);

    /**
   * Reserves the requested number of permits and returns the time that those permits can be used
   * (with one caveat).
     *
   * @return the time that the permits may be used, or, if the permits may be used immediately, an
   *     arbitrary past or present time
     */
  abstract long reserveEarliestAvailable(int permits, long nowMicros);

  @Override
  public String toString() {
    return String.format("RateLimiter[stableRate=%3.1fqps]", getRate());
  }

  @VisibleForTesting
  abstract static class SleepingStopwatch {
    /**
     * 在调用它时我们总是持有互斥锁。
     * 这很重要吗？ 也许我们需要保证每次调用reserveEarliestAvailable等都看到一个值 >= 之前的值？
     * 另外，休眠时我们不持有互斥锁是否可以？
     */
    abstract long readMicros();

    /**
     * 休眠多少毫秒
     * @param micros
     */
    abstract void sleepMicrosUninterruptibly(long micros);

    static final SleepingStopwatch createFromSystemTimer() {
      return new SleepingStopwatch() {
        final Stopwatch stopwatch = Stopwatch.createStarted();

        @Override
        long readMicros() {
          long micros = stopwatch.elapsed(MICROSECONDS);
          System.out.println("readMicros() = " + micros);
          return micros;
        }

        @Override
        void sleepMicrosUninterruptibly(long micros) {
          // 如果毫秒数大于0，则休眠该毫秒数
          if (micros > 0) {
            Uninterruptibles.sleepUninterruptibly(micros, MICROSECONDS);
          }
        }
      };
    }
  }

  private static int checkPermits(int permits) {
    checkArgument(permits > 0, "Requested permits (%s) must be positive", permits);
    return permits;
  }
}
