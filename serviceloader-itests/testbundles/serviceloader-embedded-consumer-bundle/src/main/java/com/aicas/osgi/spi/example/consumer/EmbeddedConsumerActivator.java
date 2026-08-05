/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.example.consumer;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import com.aicas.osgi.spi.example.embedded.EmbeddedConsumer;

/** Activator delegating the ServiceLoader call to the embedded JAR. */
public class EmbeddedConsumerActivator implements BundleActivator
{
  @Override
  public void start(BundleContext context)
  {
    String message = EmbeddedConsumer.firstProviderMessage();
    if (message == null)
      {
        System.out.println("[embedded consumer] No provider found.");
      }
    else
      {
        System.out.println("[embedded consumer] " + message);
      }
  }

  @Override
  public void stop(BundleContext context)
  {
    System.out.println("[embedded consumer] stopped");
  }
}
