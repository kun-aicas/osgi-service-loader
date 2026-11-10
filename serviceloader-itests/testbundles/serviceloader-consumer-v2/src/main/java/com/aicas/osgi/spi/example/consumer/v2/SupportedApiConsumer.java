/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.example.consumer.v2;

import java.util.Iterator;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.aicas.osgi.spi.example.spi.SPIProvider;

/** Exercises instance APIs supported by the OSGi ServiceLoader mediator. */
public final class SupportedApiConsumer
{
  private SupportedApiConsumer()
  {
  }

  /*---------------------------------toString()----------------------------------*/
  /**
   * Returns the diagnostic string for an SPI ServiceLoader using the supplied
   * class loader.
   *
   * @param classLoader class loader supplied to {@link ServiceLoader#load}
   * @return the ServiceLoader diagnostic string
   */
  public static String serviceLoaderToString(ClassLoader classLoader)
  {
    return ServiceLoader.load(SPIProvider.class, classLoader).toString();
  }

  /** Returns loader::toString. */
  public static String serviceLoaderToStringWithBoundReference(ClassLoader classLoader)
  {
    ServiceLoader<SPIProvider> loader = ServiceLoader.load(SPIProvider.class, classLoader);
    Supplier<String> describe = loader::toString;
    return describe.get();
  }

  /** Returns ServiceLoader::toString. */
  public static String serviceLoaderToStringWithUnboundReference(ClassLoader classLoader)
  {
    Function<ServiceLoader<SPIProvider>, String> describe = ServiceLoader::toString;
    return describe.apply(ServiceLoader.load(SPIProvider.class, classLoader));
  }

  /*---------------------------------findfirst()----------------------------------*/

  /**
   * Compares providers obtained through {@code loader.findFirst()},
   * {@code loader::findFirst}, and {@code ServiceLoader::findFirst}.
   *
   * @return true if every invocation returns the same cached provider instance
   */
  public static boolean findFirstInvocationFormsReturnSameProvider()
  {
    ServiceLoader<SPIProvider> loader = ServiceLoader.load(SPIProvider.class);
    SPIProvider direct = loader.findFirst().orElseThrow();

    Supplier<Optional<SPIProvider>> boundFindFirst = loader::findFirst;
    SPIProvider bound = boundFindFirst.get().orElseThrow();

    Function<ServiceLoader<SPIProvider>, Optional<SPIProvider>> unboundFindFirst =
            ServiceLoader::findFirst;
    SPIProvider unbound = unboundFindFirst.apply(loader).orElseThrow();

    return direct == bound && bound == unbound;
  }

  /*---------------------------------reload()----------------------------------*/
  /**
   * Checks caching and provider replacement through {@code loader.reload()}.
   *
   * @return true if lookup reuses the provider until reload and replaces it afterward.
   */
  public static boolean reloadReplacesCachedProvider()
  {
    ServiceLoader<SPIProvider> loader = ServiceLoader.load(SPIProvider.class);
    SPIProvider first = loader.findFirst().orElseThrow();
    SPIProvider cached = loader.findFirst().orElseThrow();

    loader.reload();
    SPIProvider second = loader.findFirst().orElseThrow();

    return first == cached && first != second;
  }

  /**
   * Checks {@code loader.reload()}, {@code loader::reload}, and
   * {@code ServiceLoader::reload} on the same loader.
   *
   * @return true if every invocation form replaces the preceding provider
   */
  public static boolean reloadInvocationFormsReplaceProvider()
  {
    ServiceLoader<SPIProvider> loader = ServiceLoader.load(SPIProvider.class);
    SPIProvider first = loader.findFirst().orElseThrow();

    loader.reload();
    SPIProvider second = loader.findFirst().orElseThrow();

    Runnable boundReload = loader::reload;
    boundReload.run();
    SPIProvider third = loader.findFirst().orElseThrow();

    Consumer<ServiceLoader<SPIProvider>> unboundReload = ServiceLoader::reload;
    unboundReload.accept(loader);
    SPIProvider fourth = loader.findFirst().orElseThrow();

    return first != second && second != third && third != fourth;
  }

  /*---------------------------------iterator()----------------------------------*/
  /**
   * Compares {@code loader.iterator()}, {@code loader::iterator}, and
   * {@code ServiceLoader::iterator} using the single-provider fixture.
   *
   * @return true if each iterator returns the same provider and is then exhausted.
   */
  public static boolean iteratorInvocationFormsReturnSameProvider()
  {
    ServiceLoader<SPIProvider> loader = ServiceLoader.load(SPIProvider.class);
    Iterator<SPIProvider> direct = loader.iterator();

    Supplier<Iterator<SPIProvider>> boundIterator = loader::iterator;
    Iterator<SPIProvider> bound = boundIterator.get();

    Function<ServiceLoader<SPIProvider>, Iterator<SPIProvider>> unboundIterator =
      ServiceLoader::iterator;
    Iterator<SPIProvider> unbound = unboundIterator.apply(loader);

    if (!direct.hasNext() || !bound.hasNext() || !unbound.hasNext())
      {
        return false;
      }

    SPIProvider first = direct.next();
    SPIProvider second = bound.next();
    SPIProvider third = unbound.next();
    return first == second && second == third &&
           !direct.hasNext() && !bound.hasNext() && !unbound.hasNext();
  }

}
