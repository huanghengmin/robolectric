package org.robolectric.shadows;

import static org.robolectric.annotation.LooperMode.Mode.PAUSED;
import static org.robolectric.shadow.api.Shadow.invokeConstructor;
import static org.robolectric.shadows.ShadowLooper.assertLooperMode;
import static org.robolectric.shadows.ShadowLooper.throwUnsupportedIn;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.annotation.Beta;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.Scheduler;

/**
 * A new variant of a Looper shadow that is active when {@link
 * ShadowBaseLooper#useRealisticLooper()} is enabled.
 *
 * This shadow differs from the legacy {@link ShadowLooper} in the following ways:\
 *   - Has no connection to {@link org.robolectric.util.Scheduler}. Its APIs are standalone
 *   - The main looper is always paused. Posted messages are not executed unless {@link #idle()} is
 *     called.
 *   - Just like in real Android, each looper has its own thread, and posted tasks get executed in
 *   that thread. -
 *   - There is only a single {@link SystemClock} value that all loopers read from. Unlike legacy
 *     behavior where each {@link org.robolectric.util.Scheduler} kept their own clock value.
 *
 * This class should not be used directly; use {@link ShadowBaseLooper} instead.
 */
@Implements(
    value = Looper.class,
    shadowPicker = ShadowBaseLooper.Picker.class,
    // TODO: turn off shadowOf generation. Figure out why this is needed
    isInAndroidSdk = false)
@SuppressWarnings("NewApi")
public class ShadowRealisticLooper extends ShadowBaseLooper {

  // Keep reference to all created Loopers so they can be torn down after test
  private static Set<Looper> loopingLoopers =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Looper, Boolean>()));

  @RealObject private Looper realLooper;

  @Implementation
  protected void __constructor__(boolean quitAllowed) {
    invokeConstructor(Looper.class, realLooper, from(boolean.class, quitAllowed));

    loopingLoopers.add(realLooper);
  }

  @Override
  public void quitUnchecked() {
    throwUnsupportedIn(PAUSED);
  }

  @Override
  public boolean hasQuit() {
    throwUnsupportedIn(PAUSED);
    return false;
  }

  @Override
  public void idle() {
    IdlingRunnable idlingRunnable = new IdlingRunnable(shadowQueue());
    if (Thread.currentThread() == realLooper.getThread()) {
      idlingRunnable.run();
    } else {
      if (realLooper.equals(Looper.getMainLooper())) {
        throw new UnsupportedOperationException("main looper can only be idled from main thread");
      }
      new Handler(realLooper).post(idlingRunnable);
      idlingRunnable.waitTillIdle();
    }
  }

  @Override
  public void idleFor(long time, TimeUnit timeUnit) {
    ShadowSystemClock.advanceBy(Duration.ofMillis(timeUnit.toMillis(time)));
    idle();
  }

  @Override
  public boolean isIdle() {
    return shadowQueue().isIdle();
  }

  @Override
  public void unPause() {
    throwUnsupportedIn(PAUSED);
  }

  @Override
  public boolean isPaused() {
    return true;
  }

  @Override
  public boolean setPaused(boolean shouldPause) {
    if (!shouldPause) {
      throwUnsupportedIn(PAUSED);
    }
    return true;
  }

  @Override
  public void resetScheduler() {
    throwUnsupportedIn(PAUSED);
  }

  @Override
  public void reset() {
    throwUnsupportedIn(PAUSED);
  }

  @Override
  public void idleIfPaused() {
    idle();
  }

  @Override
  public void idleConstantly(boolean shouldIdleConstantly) {
    throwUnsupportedIn(PAUSED);
  }

  @Override
  public void runOneTask() {
    Message msg = shadowQueue().poll();
    if (msg != null) {
      SystemClock.setCurrentTimeMillis(shadowMsg(msg).getWhen());
      msg.getTarget().dispatchMessage(msg);
    }
  }

  @Override
  public boolean post(Runnable runnable, long delayMillis) {
    return new Handler(realLooper).postDelayed(runnable, delayMillis);
  }

  @Override
  public boolean postAtFrontOfQueue(Runnable runnable) {
    return new Handler(realLooper).postAtFrontOfQueue(runnable);
  }

  @Override
  public void runPaused(Runnable runnable) {
    if (realLooper != Looper.getMainLooper()) {
      throw new UnsupportedOperationException("only the main looper can be paused");
    }
    // directly run, looper is always paused
    runnable.run();
    idle();
  }

  @Override
  public void pause() {
    if (realLooper != Looper.getMainLooper()) {
      throw new UnsupportedOperationException("only the main looper can be paused");
    }
  }

  @Override
  public Duration getNextScheduledTaskTime() {
    return shadowQueue().getNextScheduledTaskTime();
  }

  @Override
  public Duration getLastScheduledTaskTime() {
    return shadowQueue().getLastScheduledTaskTime();
  }

  @Resetter
  public static synchronized void resetLoopers() {
    if (!ShadowBaseLooper.useRealisticLooper()) {
      // ignore if not realistic looper
      return;
    }

    Collection<Looper> loopersCopy = new ArrayList(loopingLoopers);
    for (Looper looper : loopersCopy) {
      ShadowRealisticMessageQueue shadowRealisticMessageQueue = Shadow.extract(looper.getQueue());
      if (shadowRealisticMessageQueue.isQuitAllowed()) {
        looper.quit();
        loopingLoopers.remove(looper);
      } else {
        shadowRealisticMessageQueue.reset();
      }
    }
  }

  @Override
  public Scheduler getScheduler() {
    throwUnsupportedIn(PAUSED);
    return null;
  }

  private static ShadowRealisticMessage shadowMsg(Message msg) {
    return Shadow.extract(msg);
  }

  private ShadowRealisticMessageQueue shadowQueue() {
    return Shadow.extract(realLooper.getQueue());
  }

  private static class IdlingRunnable implements Runnable {

    private final CountDownLatch runLatch = new CountDownLatch(1);
    private final ShadowRealisticMessageQueue shadowQueue;

    public IdlingRunnable(ShadowRealisticMessageQueue shadowQueue) {
      this.shadowQueue = shadowQueue;
    }

    public void waitTillIdle() {
      try {
        runLatch.await();
      } catch (InterruptedException e) {
        Log.w("ShadowRealisticLooper", "wait till idle interrupted");
      }
    }

    @Override
    public void run() {
      while (!shadowQueue.isIdle()) {
        Message msg = shadowQueue.getNext();
        msg.getTarget().dispatchMessage(msg);
        shadowMsg(msg).recycleQuietly();
      }
      runLatch.countDown();
    }
  }
}