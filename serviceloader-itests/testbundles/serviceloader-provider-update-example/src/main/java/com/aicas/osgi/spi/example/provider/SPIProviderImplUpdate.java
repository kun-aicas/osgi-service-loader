/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.example.provider;

import com.aicas.osgi.spi.example.spi.SPIProvider;

/** Provider implementation contributed by the updated bundle revision. */
public class SPIProviderImplUpdate implements SPIProvider
{
  @Override
  public String getMessage()
  {
    return "Hello, I was provided by the updated provider revision.";
  }
}
