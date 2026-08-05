/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.example.provider;

import com.aicas.osgi.spi.example.spi.SPIProvider;

/** Provider whose use deliberately fails for integration testing. */
public class SPIProviderImplFailure implements SPIProvider
{
  /** Creates a provider that fails before it can be returned to a consumer. */
  public SPIProviderImplFailure()
  {
    System.out.println("[failing provider] construction attempted");
    throw new IllegalStateException("failure from test provider");
  }

  @Override
  public String getMessage()
  {
    return "This message must never be returned.";
  }
}
