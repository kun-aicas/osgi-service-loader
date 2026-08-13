/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.example.consumer;

import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import com.aicas.osgi.spi.example.spi.SPIProvider;

/** Untreated host whose consumer metadata is contributed by a fragment. */
public class FragmentConsumerHostActivator implements BundleActivator
{
  @Override
  public void start(BundleContext context)
  {
    System.out.println("[fragment consumer host] First SPI provider:");
    try
      {
        ServiceLoader.load(SPIProvider.class).findFirst().ifPresentOrElse(
            provider -> System.out.println(provider.getMessage()),
            () -> System.out.println("[fragment consumer host] No provider found."));
      }
    catch (ServiceConfigurationError e)
      {
        System.err.println("[fragment consumer host] Failed to load SPI provider");
        e.printStackTrace();
      }
  }

  @Override
  public void stop(BundleContext context)
  {
    System.out.println("[fragment consumer host] stopped");
  }
}
