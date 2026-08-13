/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;

import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicas.osgi.spi.proxy.internal.MediatorActivator;

/**
 * Unit tests for the combined ServiceLoader.
 */
public class ServiceLoaderTest
{
  /**
   * Unit tests when the Service Loader Mediator is unavailable and using
   * Java-delegate path of the combined ServiceLoader.
   */
  @Test
  public void loadUsesTheJavaDelegateWhenMediatorIsUnavailable()
  {
    ServiceLoader<TestService> loader = ServiceLoader.load(TestService.class);

    Iterator<TestService> providers = loader.iterator();

    assertTrue(providers.hasNext());
    TestService provider = providers.next();
    assertTrue(provider instanceof JavaProvider);
    assertFalse(providers.hasNext());
  }

  @Test
  public void loadWithExplicitClassLoaderUsesThatLoader()
  {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    ServiceLoader<TestService> loader =
        ServiceLoader.load(TestService.class, classLoader);

    Optional<TestService> provider = loader.findFirst();

    assertTrue(provider.isPresent());
    assertTrue(provider.get() instanceof JavaProvider);
  }

  @Test
  public void reloadCreatesFreshJavaProviderInstances()
  {
    ServiceLoader<TestService> loader = ServiceLoader.load(TestService.class);

    TestService first = loader.findFirst().get();
    loader.reload();
    TestService second = loader.findFirst().get();
    assertNotSame(first, second);
  }

  @Test
  public void getCachedJavaProviderInstances()
  {
    ServiceLoader<TestService> loader = ServiceLoader.load(TestService.class);
    TestService first = loader.findFirst().get();
    TestService second = loader.findFirst().get();
    assertSame(first, second);
  }

  @Test
  public void findFirstReturnsEmptyWhenNoProviderExists()
  {
    ServiceLoader<Runnable> loader = ServiceLoader.load(Runnable.class);
    assertEquals(Optional.empty(), loader.findFirst());
  }

 @Test
  public void iteratorNextThrowsWhenNoProviderRemains()
  {
    ServiceLoader<Runnable> loader = ServiceLoader.load(Runnable.class);
    Iterator<Runnable> iterator = loader.iterator();

    assertFalse(iterator.hasNext());
    try
      {
        iterator.next();
      }
    catch (NoSuchElementException expected)
      {
        return;
      }
    throw new AssertionError("Expected NoSuchElementException");
  }


  @Test(expected = NullPointerException.class)
  public void loadWithNullClassLoaderFollowsJavaDelegateContract()
  {
    ServiceLoader.load(TestService.class, null);
  }

  /**
   * Exercises the consumer-to-mediator-to-provider path with bundle-shaped
   * test fixtures. The provider is loaded through the provider bundle wiring,
   * so this does not accidentally pass by finding the provider in the test
   * class path's {@code META-INF/services} directory.
   */
  @Test
  public void consumerLoadsProviderRegisteredByMediator() throws Exception
  {
    MediatorActivator mediator = new MediatorActivator();
    MediatorActivator.logger_ = mock(org.osgi.service.log.Logger.class);

    Bundle providerBundle = mock(Bundle.class);
    Bundle consumerBundle = mock(Bundle.class);
    BundleContext context = mock(BundleContext.class);
    BundleRevision revision = mock(BundleRevision.class);
    BundleWiring wiring = mock(BundleWiring.class);

    when(providerBundle.getBundleId()).thenReturn(17L);
    when(providerBundle.getState()).thenReturn(Bundle.ACTIVE);
    when(providerBundle.adapt(BundleRevision.class)).thenReturn(revision);
    when(providerBundle.adapt(BundleWiring.class)).thenReturn(wiring);
    when(wiring.getClassLoader()).thenReturn(getClass().getClassLoader());
    when(revision.getBundle()).thenReturn(providerBundle);
    when(context.getBundle(17L)).thenReturn(providerBundle);

    java.lang.reflect.Field contextField =
        MediatorActivator.class.getDeclaredField("bundleContext_");
    contextField.setAccessible(true);
    contextField.set(mediator, context);

    mediator.registerConsumerBundle(consumerBundle,
                                    Collections.singleton(TestService.class.getName()));
    mediator.registerProviderBundle(TestService.class.getName(),
                                    providerBundle,
                                    Collections.singleton(BundleProvider.class.getName()));
    MediatorActivator.activator_ = mediator;

    try
      {
        ServiceLoader<TestService> loader = ServiceLoader.load(TestService.class);
        TestService provider = loader.findFirst().get();

        assertTrue(mediator.requiresProcessing(consumerBundle));
        assertTrue(provider instanceof BundleProvider);
        assertEquals("BundleProvider", provider.value());

        // should return the cached service.
        TestService provider2 = loader.findFirst().get();
        assertSame(provider, provider2);

        // after reload, the provider should be a new instance.
        loader.reload();
        TestService provider3 = loader.findFirst().get();
        assertNotSame(provider, provider3);
      }
    finally
      {
        MediatorActivator.activator_ = null;
      }
  }

  /**
   * A provider stop keeps its mediator definition so a later lookup may start
   * the provider bundle again. It must not, however, reuse the instance that
   * was cached before the stop. The same rule applies when {@code hasNext()}
   * prepared that old instance immediately before the lifecycle change.
   */
  @Test
  public void providerStopInvalidatesCachedAndPreparedProviderInstances()
      throws Exception
  {
    MediatorActivator mediator = new MediatorActivator();
    MediatorActivator.logger_ = mock(org.osgi.service.log.Logger.class);
    Bundle providerBundle = mock(Bundle.class);
    BundleContext context = mock(BundleContext.class);
    BundleRevision revision = mock(BundleRevision.class);
    BundleWiring wiring = mock(BundleWiring.class);
    AtomicInteger state = new AtomicInteger(Bundle.ACTIVE);

    when(providerBundle.getBundleId()).thenReturn(18L);
    when(providerBundle.getState()).thenAnswer(invocation -> state.get());
    doAnswer(invocation ->
      {
        state.set(Bundle.ACTIVE);
        return null;
      }).when(providerBundle).start();
    when(providerBundle.adapt(BundleRevision.class)).thenReturn(revision);
    when(providerBundle.adapt(BundleWiring.class)).thenReturn(wiring);
    when(wiring.getClassLoader()).thenReturn(getClass().getClassLoader());
    when(revision.getBundle()).thenReturn(providerBundle);
    when(context.getBundle(18L)).thenReturn(providerBundle);

    java.lang.reflect.Field contextField =
        MediatorActivator.class.getDeclaredField("bundleContext_");
    contextField.setAccessible(true);
    contextField.set(mediator, context);

    mediator.registerProviderBundle(TestService.class.getName(),
                                    providerBundle,
                                    Collections.singleton(BundleProvider.class.getName()));
    MediatorActivator.activator_ = mediator;

    try
      {
        ServiceLoader<TestService> loader = ServiceLoader.load(TestService.class);
        TestService cachedProvider = loader.findFirst().get();

        Iterator<TestService> iterator = loader.iterator();
        assertTrue(iterator.hasNext());

        state.set(Bundle.RESOLVED);
        mediator.providerBundleStopping(providerBundle);

        TestService refreshedProvider = iterator.next();

        assertNotSame(cachedProvider, refreshedProvider);
        assertEquals("BundleProvider", refreshedProvider.value());
        verify(providerBundle).start();
      }
    finally
      {
        MediatorActivator.activator_ = null;
      }
  }

  /**
   * Provider generations are scoped by Service Type. Registering an unrelated
   * provider must not discard a cached provider of this loader's Service Type.
   */
  @Test
  public void unrelatedProviderRegistrationDoesNotInvalidateProviderCache()
      throws Exception
  {
    MediatorActivator mediator = new MediatorActivator();
    MediatorActivator.logger_ = mock(org.osgi.service.log.Logger.class);
    Bundle providerBundle = mock(Bundle.class);
    BundleContext context = mock(BundleContext.class);
    BundleRevision revision = mock(BundleRevision.class);
    BundleWiring wiring = mock(BundleWiring.class);

    when(providerBundle.getBundleId()).thenReturn(19L);
    when(providerBundle.getState()).thenReturn(Bundle.ACTIVE);
    when(providerBundle.adapt(BundleRevision.class)).thenReturn(revision);
    when(providerBundle.adapt(BundleWiring.class)).thenReturn(wiring);
    when(wiring.getClassLoader()).thenReturn(getClass().getClassLoader());
    when(revision.getBundle()).thenReturn(providerBundle);
    when(context.getBundle(19L)).thenReturn(providerBundle);

    java.lang.reflect.Field contextField =
        MediatorActivator.class.getDeclaredField("bundleContext_");
    contextField.setAccessible(true);
    contextField.set(mediator, context);

    mediator.registerProviderBundle(TestService.class.getName(),
                                    providerBundle,
                                    Collections.singleton(BundleProvider.class.getName()));
    MediatorActivator.activator_ = mediator;

    try
      {
        ServiceLoader<TestService> loader = ServiceLoader.load(TestService.class);
        TestService cachedProvider = loader.findFirst().get();

        mediator.registerProviderBundle(Runnable.class.getName(),
                                        providerBundle,
                                        Collections.singleton(RunnableProvider.class.getName()));

        assertSame(cachedProvider, loader.findFirst().get());
      }
    finally
      {
        MediatorActivator.activator_ = null;
      }
  }

  public interface TestService
  {
    public String value();
  }

  public static final class JavaProvider implements TestService
  {
    public JavaProvider()
    {
    }

    @Override
    public String value()
    {
      return "JavaProvider";
    }
  }

  /** Provider implementation deliberately exposed only through bundle wiring. */
  public static final class BundleProvider implements TestService
  {
    public BundleProvider()
    {
    }

    @Override
    public String value()
    {
      return "BundleProvider";
    }
  }

  public static final class RunnableProvider implements Runnable
  {
    @Override
    public void run()
    {
    }
  }
}
