/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/
package com.aicas.osgi.spi.example.consumer;

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import com.aicas.osgi.spi.example.spi.SPIProvider;

/**
 * A bundle that contains both a ServiceLoader consumer and provider.
 */
public class ServiceloaderProviderConsumerExampleActivator implements BundleActivator
{
  @Override
  public void start(BundleContext context)
  {
    System.out.println("\n[provider-consumer] Result from the first SPI:\n");
    try
      {
        Iterator<SPIProvider> providers =
            ServiceLoader.load(SPIProvider.class).iterator();
        if (providers.hasNext())
          {
            SPIProvider provider = providers.next();
            System.out.println(provider.getMessage());
          }
        else
          {
            System.out.println("[provider-consumer] No SPI provider found.");
          }
      }
    catch (ServiceConfigurationError e)
      {
        System.err.println("[provider-consumer] Failed to load the first SPI provider");
        e.printStackTrace();
      }
  }

  @Override
  public void stop(BundleContext context)
  {
    System.out.println("[provider-consumer] stopped");
  }
}
