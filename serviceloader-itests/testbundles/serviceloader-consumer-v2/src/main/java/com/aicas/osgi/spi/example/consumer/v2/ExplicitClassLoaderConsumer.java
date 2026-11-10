/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.example.consumer.v2;

import java.util.ServiceLoader;
import java.util.function.BiFunction;

import com.aicas.osgi.spi.example.spi.SPIProvider;

/**
 * Exercises {@link ServiceLoader#load(Class, ClassLoader)}.
 * The consumer Bundle's own loader enables mediated discovery; a different
 * loader retains normal JDK lookup.
 */
public final class ExplicitClassLoaderConsumer
{
  private ExplicitClassLoaderConsumer()
  {
  }

  /**
   * Performs {@link ServiceLoader#load(Class, ClassLoader)} for the SPI using
   * the supplied class loader and prints the lookup result.
   *
   * <p>If a provider is found, its message is printed. Otherwise, a
   * no-provider message is printed.</p>
   *
   * @param classLoader class loader supplied to {@link ServiceLoader#load}
   */
  public static void loadServiceWithGivenClassLoader(ClassLoader classLoader)
  {
    ServiceLoader.load(SPIProvider.class, classLoader).findFirst().
                  ifPresentOrElse(provider -> System.out.println(provider.getMessage()),
                                  () -> System.out.println("[explicitClassLoaderConsumer] No provider found."));
  }

  /**
   * Performs {@code ServiceLoader.load(Class, ClassLoader)} through
   * {@code ServiceLoader::load} and prints the lookup result.
   *
   * @param classLoader the requested loader, or null for JDK system-loader lookup
   */
  public static void loadServiceWithGivenClassLoaderUsingStaticReference(ClassLoader classLoader)
  {
    BiFunction<Class<SPIProvider>, ClassLoader, ServiceLoader<SPIProvider>> load =
      ServiceLoader::load;
    load.apply(SPIProvider.class, classLoader).findFirst().
      ifPresentOrElse(provider -> System.out.println(provider.getMessage()),
                      () -> System.out.println("[explicitClassLoaderConsumer] No provider found."));
  }
}
