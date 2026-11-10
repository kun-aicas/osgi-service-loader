/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.example.consumer.v2;

import java.util.ServiceLoader;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.aicas.osgi.spi.example.spi.SPIProvider;

/** Exercises JDK ServiceLoader APIs unsupported by the mediator proxy. */
public final class UnsupportedApiConsumer
{
  private UnsupportedApiConsumer()
  {
  }

  /*---------------------------------stream()----------------------------------*/

  /** Executes a normal JDK ServiceLoader stream lookup. */
  public static void loadFirstProvider()
  {
    ServiceLoader.load(SPIProvider.class).stream().findFirst();
  }

  /** Executes a JDK stream lookup through {@code loader::stream}. */
  public static void loadFirstProviderWithBoundStreamReference()
  {
    ServiceLoader<SPIProvider> loader = ServiceLoader.load(SPIProvider.class);
    Supplier<Stream<ServiceLoader.Provider<SPIProvider>>> stream = loader::stream;
    stream.get().findFirst();
  }

  /** Executes a JDK stream lookup through {@code ServiceLoader::stream}. */
  public static void loadFirstProviderWithUnboundStreamReference()
  {
    Function<ServiceLoader<SPIProvider>, Stream<ServiceLoader.Provider<SPIProvider>>> stream =
      ServiceLoader::stream;
    stream.apply(ServiceLoader.load(SPIProvider.class)).findFirst();
  }

  /*---------------------------------load(ModuleLayer, Class)----------------------------------*/
  /**
   * Executes {@code ServiceLoader.load(ModuleLayer, Class)} in the boot layer.
   *
   * @return true if the JDK loader finds no OSGi provider in the boot module layer
   */
  public static boolean moduleLayerLookupFindsNoOsgiProvider()
  {
    ServiceLoader<SPIProvider> loader =
      ServiceLoader.load(ModuleLayer.boot(), SPIProvider.class);
    return loader.getClass() == ServiceLoader.class && loader.findFirst().isEmpty();
  }

  /**
   * Executes {@code ServiceLoader.load(ModuleLayer, Class)} through
   * the static method reference {@code ServiceLoader::load}.
   *
   * @return true if the JDK loader finds no OSGi provider in the boot module layer
   */
  public static boolean moduleLayerLookupWithStaticReferenceFindsNoOsgiProvider()
  {
    BiFunction<ModuleLayer, Class<SPIProvider>, ServiceLoader<SPIProvider>> load =
      ServiceLoader::load;
    ServiceLoader<SPIProvider> loader = load.apply(ModuleLayer.boot(), SPIProvider.class);
    return loader.getClass() == ServiceLoader.class && loader.findFirst().isEmpty();
  }

  /*---------------------------------loadInstalled(Class)----------------------------------*/
  /**
   * Executes {@code ServiceLoader.loadInstalled(Class)} using the JDK lookup.
   *
   * @return true if the JDK loader finds no OSGi provider through the platform loader
   */
  public static boolean installedLookupFindsNoOsgiProvider()
  {
    ServiceLoader<SPIProvider> loader = ServiceLoader.loadInstalled(SPIProvider.class);
    return loader.getClass() == ServiceLoader.class && loader.findFirst().isEmpty();
  }

  /**
   * Executes {@code ServiceLoader.loadInstalled(Class)} through
   * the static method reference {@code ServiceLoader::loadInstalled}.
   *
   * @return true if the JDK loader finds no OSGi provider through the platform loader
   */
  public static boolean installedLookupWithStaticReferenceFindsNoOsgiProvider()
  {
    Function<Class<SPIProvider>, ServiceLoader<SPIProvider>> loadInstalled =
      ServiceLoader::loadInstalled;
    ServiceLoader<SPIProvider> loader = loadInstalled.apply(SPIProvider.class);
    return loader.getClass() == ServiceLoader.class && loader.findFirst().isEmpty();
  }

}
