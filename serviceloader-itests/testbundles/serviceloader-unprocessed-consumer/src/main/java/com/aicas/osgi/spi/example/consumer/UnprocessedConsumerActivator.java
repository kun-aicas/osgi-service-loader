/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.example.consumer;

import java.util.ServiceLoader;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import com.aicas.osgi.spi.example.spi.SPIProvider;

/** Consumer with valid ServiceLoader calls but no mediator consumer metadata. */
public class UnprocessedConsumerActivator implements BundleActivator
{
  @Override
  public void start(BundleContext context)
  {
    System.out.println("[unprocessed consumer] Looking for an SPI provider:");
    ServiceLoader.load(SPIProvider.class).findFirst().ifPresentOrElse(
        provider -> System.out.println(provider.getMessage()),
        () -> System.out.println("[unprocessed consumer] No provider found."));
  }

  @Override
  public void stop(BundleContext context)
  {
    System.out.println("[unprocessed consumer] stopped");
  }
}
