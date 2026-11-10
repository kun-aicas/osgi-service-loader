/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.example.consumer;

import java.util.ServiceLoader;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/** Updated consumer whose metadata and ServiceLoader call use Runnable. */
public class UpdatedConsumerActivator implements BundleActivator
{
  @Override
  public void start(BundleContext context)
  {
    ServiceLoader.load(Runnable.class).findFirst().ifPresentOrElse(
        runnable ->
        {
          runnable.run();
          System.out.println("[updated consumer] Runnable provider found.");
        },
        () -> System.out.println("[updated consumer] No Runnable provider found."));
  }

  @Override
  public void stop(BundleContext context)
  {
    System.out.println("[updated consumer] stopped");
  }
}
