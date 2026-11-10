/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/
package com.aicas.osgi.spi.example.client;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import com.aicas.osgi.spi.example.spi.SPIProvider;

import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Function;

public class ServiceloaderConsumerExampleActivator
        implements BundleActivator
{
  @Override
  public void start(BundleContext context)
  {
    boolean found = false;
    Function<Class<SPIProvider>, ServiceLoader<SPIProvider>> load =
            ServiceLoader::load;
    ServiceLoader<SPIProvider> loader = load.apply(SPIProvider.class);
    System.out.println("\n [consumer] Get all the registered services:/");
    try
    {
      for (SPIProvider provider : loader)
      {
        found = true;
        System.out.println(provider.getMessage());
      }
    }
    catch (ServiceConfigurationError e)
    {
      System.err.println("[consumer] Failed to load SPI provider");
      e.printStackTrace();
    }
    if (!found)
    {
      System.out.println("[consumer] No provider found.");
    }
  }

  @Override
  public void stop(BundleContext context)
  {
    System.out.println("[consumer] stopped");
  }
}
