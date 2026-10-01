/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.example.consumer.dynamicimport.v2;

import java.util.ServiceLoader;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import com.aicas.osgi.spi.example.spi.SPIProvider;

/** Loads the SPI after its package is dynamically imported by this Bundle. */
public class DynamicImportConsumerActivator implements BundleActivator
{
  @Override
  public void start(BundleContext context)
  {
    ServiceLoader.load(SPIProvider.class).findFirst().ifPresentOrElse(
        provider -> System.out.println(provider.getMessage()),
        () -> System.out.println("[dynamicImportConsumer] No SPI 2.0 provider found."));
  }

  @Override
  public void stop(BundleContext context)
  {
    System.out.println("[dynamicImportConsumer] stopped");
  }
}
