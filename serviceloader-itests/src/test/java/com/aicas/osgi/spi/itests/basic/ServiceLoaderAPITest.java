/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.itests.basic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;

import com.aicas.osgi.spi.itests.support.AbstractTest;

/** Verifies explicit-class-loader lookup, instance APIs, and unsupported JDK API paths. */
public class ServiceLoaderAPITest extends AbstractTest
{
  private Bundle provider_;
  private Bundle consumer_;
  private Class<?> explicitClassLoaderConsumer_;
  private Class<?> supportedApiConsumer_;
  private Class<?> unsupportedApiConsumer_;
  private static String SERVICELOADER_STRING =
      "com.aicas.osgi.spi.proxy.ServiceLoader[com.aicas.osgi.spi.example.spi.SPIProvider]";

  /** Starts the version 2 provider and consumer Bundles for each test. */
  @Before
  public void startV2Bundles()
    throws Exception
  {
    provider_ = installTestBundle(BUNDLE_PROVIDER_V2);
    consumer_ = installTestBundle(BUNDLE_CONSUMER_V2);

    provider_.start();
    consumer_.start();
    explicitClassLoaderConsumer_ = consumer_.loadClass(EXPLICIT_CLASS_LOADER_CONSUMER);
    supportedApiConsumer_ = consumer_.loadClass(SUPPORTED_API_CONSUMER);
    unsupportedApiConsumer_ = consumer_.loadClass(UNSUPPORTED_API_CONSUMER);
  }

  /** Stops the consumer and provider after each test. */
  @After
  public void stopV2Bundles()
    throws Exception
  {
    consumer_.stop();
    provider_.stop();
  }

  /*----------------------load(service, classloader)-------------------------*/
  /**
   * Verifies R11: an explicit lookup with the consumer Bundle's own class
   * loader remains mediated and discovers the version 2 provider.
   */
  @Test
  public void consumerV2FindsProviderWithOwnBundleClassLoader()
    throws Exception
  {
    assertExplicitClassLoaderLookup("loadServiceWithGivenClassLoader",
                                    explicitClassLoaderConsumer_.getClassLoader(),
                                    V2_PROVIDER_MESSAGE);
  }


  /** Verifies {@code ServiceLoader::load} mediates the consumer Bundle's own loader. */
  @Test
  public void consumerV2StaticReferenceFindsProviderWithOwnBundleClassLoader()
          throws Exception
  {
    assertExplicitClassLoaderLookup("loadServiceWithGivenClassLoaderUsingStaticReference",
            explicitClassLoaderConsumer_.getClassLoader(),
            V2_PROVIDER_MESSAGE);
  }


  /**
   * Verifies R11: a context class loader distinct from the consumer Bundle's
   * class loader uses normal JDK ServiceLoader lookup and finds no provider.
   */
  @Test
  public void consumerV2DoesNotFindProviderWithContextClassLoader()
    throws Exception
  {
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    assertNotSame("Context class loader must not be the consumer Bundle loader",
                  explicitClassLoaderConsumer_.getClassLoader(), contextClassLoader);

    assertExplicitClassLoaderLookup("loadServiceWithGivenClassLoader",
                                    contextClassLoader,
                                    "[explicitClassLoaderConsumer] No provider found.");
  }

  /** Verifies {@code ServiceLoader::load} retains JDK lookup for the context loader. */
  @Test
  public void consumerV2StaticReferenceDoesNotFindProviderWithContextClassLoader()
    throws Exception
  {
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    assertNotSame("Context class loader must not be the consumer Bundle loader",
                  explicitClassLoaderConsumer_.getClassLoader(), contextClassLoader);

    assertExplicitClassLoaderLookup("loadServiceWithGivenClassLoaderUsingStaticReference",
                                    contextClassLoader,
                                    "[explicitClassLoaderConsumer] No provider found.");
  }

  /** Verifies {@code ServiceLoader.load(Class, ClassLoader)} accepts a null loader. */
  @Test
  public void consumerV2DoesNotFindProviderWithNullClassLoader()
    throws Exception
  {
    assertExplicitClassLoaderLookup("loadServiceWithGivenClassLoader", null,
                                    "[explicitClassLoaderConsumer] No provider found.");
  }

  /** Verifies {@code ServiceLoader::load} accepts a null loader. */
  @Test
  public void consumerV2StaticReferenceDoesNotFindProviderWithNullClassLoader()
    throws Exception
  {
    assertExplicitClassLoaderLookup("loadServiceWithGivenClassLoaderUsingStaticReference", null,
                                    "[explicitClassLoaderConsumer] No provider found.");
  }

  private void assertExplicitClassLoaderLookup(String methodName, ClassLoader classLoader,
                                               String expectedOutput)
    throws Exception
  {
    int outputOffset = outputLength();
    explicitClassLoaderConsumer_.getMethod(methodName, ClassLoader.class).
      invoke(null, (Object) classLoader);

    awaitOutputContainsAfter(outputOffset, expectedOutput);
  }

  /*---------------------------------toString()---------------------------------*/

  /**
   * Verifies that the supported {@link java.util.ServiceLoader#toString()}
   * invocation is remapped to the mediator proxy.
   */
  @Test
  public void consumerV2ServiceLoaderToStringUsesProxy()
    throws Exception
  {
    Object value = supportedApiConsumer_.getMethod("serviceLoaderToString",
                                                  ClassLoader.class).
                                        invoke(null, supportedApiConsumer_.getClassLoader());

    assertEquals(SERVICELOADER_STRING,
                 value);
  }

  /** Verifies {@code loader::toString}. */
  @Test
  public void consumerV2BoundToStringReferenceUsesProxy()
    throws Exception
  {
    Object value = supportedApiConsumer_.getMethod("serviceLoaderToStringWithBoundReference",
                                                  ClassLoader.class).
                                        invoke(null, supportedApiConsumer_.getClassLoader());

    assertEquals(SERVICELOADER_STRING,
                 value);
  }

  /** Verifies {@code ServiceLoader::toString}. */
  @Test
  public void consumerV2UnboundToStringReferenceUsesProxy()
    throws Exception
  {
    Object value = supportedApiConsumer_.getMethod("serviceLoaderToStringWithUnboundReference",
                                                  ClassLoader.class).
                                        invoke(null, supportedApiConsumer_.getClassLoader());

    assertEquals(SERVICELOADER_STRING,
                 value);
  }

  /**
   * Verifies {@code loader.iterator()}, {@code loader::iterator}, and
   * {@code ServiceLoader::iterator} return the same single provider.
   */
  @Test
  public void consumerV2IteratorInvocationFormsReturnSameProvider()
    throws Exception
  {
    boolean sameProvider = (Boolean) supportedApiConsumer_.
      getMethod("iteratorInvocationFormsReturnSameProvider").invoke(null);

    assertTrue("Iterator invocation forms must return the same provider and then be exhausted",
               sameProvider);
  }

  /*---------------------------------findfirst()----------------------------------*/
  /**
   * Verifies {@code loader.findFirst()}, {@code loader::findFirst}, and
   * {@code ServiceLoader::findFirst} return the same provider instance.
   */
  @Test
  public void consumerV2FindFirstInvocationFormsReturnSameProvider()
    throws Exception
  {
    boolean sameProvider = (Boolean) supportedApiConsumer_.
      getMethod("findFirstInvocationFormsReturnSameProvider").invoke(null);

    assertTrue("findFirst invocation forms returned different provider instances",
               sameProvider);
  }

  /*---------------------------------reload()----------------------------------*/
  /**
   * Verifies that reloading a mediated ServiceLoader replaces its cached
   * provider instance.
   */
  @Test
  public void consumerV2ReloadReplacesCachedProvider()
    throws Exception
  {
    boolean replaced = (Boolean) supportedApiConsumer_.
      getMethod("reloadReplacesCachedProvider").invoke(null);

    assertTrue("Reload did not preserve caching before reload and replace the " +
               "provider afterward",
               replaced);
  }

  /**
   * Verifies {@code loader.reload()}, {@code loader::reload}, and
   * {@code ServiceLoader::reload} each replace the provider.
   */
  @Test
  public void consumerV2ReloadInvocationFormsReplaceProvider()
    throws Exception
  {
    boolean replaced = (Boolean) supportedApiConsumer_.
      getMethod("reloadInvocationFormsReplaceProvider").invoke(null);

    assertTrue("A reload invocation form did not replace the provider",
               replaced);
  }

  /*---------------------------------stream()----------------------------------------*/
  /**
   * Verifies that {@code loader.stream()} executes without a weaving error.
   */
  @Test
  public void consumerV2StreamRunsWithoutWeaving()
          throws Exception
  {
    unsupportedApiConsumer_.getMethod("loadFirstProvider").invoke(null);
  }

  /** Verifies that {@code loader::stream} executes without a weaving error. */
  @Test
  public void consumerV2BoundStreamReferenceRunsWithoutWeaving()
          throws Exception
  {
    unsupportedApiConsumer_.getMethod("loadFirstProviderWithBoundStreamReference").invoke(null);
  }

  /** Verifies that {@code ServiceLoader::stream} executes without a weaving error. */
  @Test
  public void consumerV2UnboundStreamReferenceRunsWithoutWeaving()
          throws Exception
  {
    unsupportedApiConsumer_.getMethod("loadFirstProviderWithUnboundStreamReference").invoke(null);
  }

  /*---------------------------------load(ModuleLayer, Class)----------------------------------*/
  /** Verifies {@code ServiceLoader.load(ModuleLayer, Class)} retains JDK lookup. */
  @Test
  public void consumerV2ModuleLayerLookupFindsNoOsgiProvider()
    throws Exception
  {
    boolean jdkLookup = (Boolean) unsupportedApiConsumer_.
      getMethod("moduleLayerLookupFindsNoOsgiProvider").invoke(null);

    assertTrue("Module-layer lookup must use the JDK loader and find no OSGi provider",
               jdkLookup);
  }

  /** Verifies {@code ServiceLoader::load} for module-layer lookup retains JDK behavior. */
  @Test
  public void consumerV2ModuleLayerStaticReferenceFindsNoOsgiProvider()
    throws Exception
  {
    boolean jdkLookup = (Boolean) unsupportedApiConsumer_.
      getMethod("moduleLayerLookupWithStaticReferenceFindsNoOsgiProvider").invoke(null);

    assertTrue("Module-layer method reference must use the JDK loader and find no OSGi provider",
               jdkLookup);
  }

  /*---------------------------------loadInstalled(Class)----------------------------------*/

  /** Verifies {@code ServiceLoader.loadInstalled(Class)} retains JDK lookup. */
  @Test
  public void consumerV2InstalledLookupFindsNoOsgiProvider()
    throws Exception
  {
    boolean jdkLookup = (Boolean) unsupportedApiConsumer_.
      getMethod("installedLookupFindsNoOsgiProvider").invoke(null);

    assertTrue("Installed lookup must use the JDK loader and find no OSGi provider",
               jdkLookup);
  }

  /** Verifies {@code ServiceLoader::loadInstalled} retains JDK lookup. */
  @Test
  public void consumerV2InstalledStaticReferenceFindsNoOsgiProvider()
    throws Exception
  {
    boolean jdkLookup = (Boolean) unsupportedApiConsumer_.
      getMethod("installedLookupWithStaticReferenceFindsNoOsgiProvider").invoke(null);

    assertTrue("Installed method reference must use the JDK loader and find no OSGi provider",
               jdkLookup);
  }

}
