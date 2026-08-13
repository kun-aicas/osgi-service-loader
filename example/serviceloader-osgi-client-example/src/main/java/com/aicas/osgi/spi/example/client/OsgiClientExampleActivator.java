/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/
package com.aicas.osgi.spi.example.client;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import com.aicas.osgi.spi.example.spi.SPIProvider;


public class OsgiClientExampleActivator implements BundleActivator
{
  @Override
  public void start(BundleContext context) {
      // get service from service registry.
      ServiceReference<SPIProvider> reference =
              context.getServiceReference(SPIProvider.class);
      if (reference == null)
      {
          System.out.println("[osgi client] SPIProvider service not found");
          return;
      }
      SPIProvider provider = context.getService(reference);
      if (provider == null)
      {
          System.out.println("[osgi client] Failed to obtain SPIProvider service");
          return;
      }
      try
      {
          System.out.println("[osgi client] - " + provider.getMessage());
      }
      finally
      {
          context.ungetService(reference);
      }
  }

  @Override
  public void stop(BundleContext context)
  {
      System.out.println("[osgi client] Stopped");
  }
}
