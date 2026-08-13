/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.example.provider;

import com.aicas.osgi.spi.example.spi.SPIProvider;

/** Provider implementation contributed by the updated module-info revision. */
public class SPIProviderImplModuleInfoUpdate implements SPIProvider
{
  @Override
  public String getMessage()
  {
    return "Hi, I was declared by the updated module-info.java revision.";
  }
}
