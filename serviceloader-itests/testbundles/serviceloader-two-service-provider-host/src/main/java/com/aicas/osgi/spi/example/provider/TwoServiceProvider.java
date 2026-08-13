/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.example.provider;

import com.aicas.osgi.spi.example.spi.SPIProvider;

/** Provider implementation used by the host-and-fragment metadata test. */
public class TwoServiceProvider implements SPIProvider, Runnable
{
  @Override
  public String getMessage()
  {
    return "Hello from the two-service provider.";
  }

  @Override
  public void run()
  {
    // The test only needs this implementation to be instantiable as Runnable.
  }
}
