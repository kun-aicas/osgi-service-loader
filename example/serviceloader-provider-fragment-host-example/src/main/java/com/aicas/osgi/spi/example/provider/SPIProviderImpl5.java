/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.example.provider;

import com.aicas.osgi.spi.example.spi.SPIProvider;

/** Provider implementation supplied by the fragment host bundle. */
public class SPIProviderImpl5 implements SPIProvider
{
  @Override
  public String getMessage()
  {
    return "Hello, I was declared by META-INF/services in the fragment host.";
  }
}
