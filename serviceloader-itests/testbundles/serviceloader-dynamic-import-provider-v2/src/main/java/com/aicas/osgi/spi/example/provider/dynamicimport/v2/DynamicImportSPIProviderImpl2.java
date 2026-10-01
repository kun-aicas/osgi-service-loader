/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.example.provider.dynamicimport.v2;

import com.aicas.osgi.spi.example.spi.SPIProvider;

/** Provides the SPI after its package is dynamically imported by this Bundle. */
public class DynamicImportSPIProviderImpl2 implements SPIProvider
{
  @Override
  public String getMessage()
  {
    return "Hello, I was provided by SPI 2.0 through DynamicImport-Package.";
  }
}
