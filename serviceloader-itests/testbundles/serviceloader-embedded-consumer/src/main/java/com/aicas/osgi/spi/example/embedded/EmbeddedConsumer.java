/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.example.embedded;

import java.util.ServiceLoader;

import com.aicas.osgi.spi.example.spi.SPIProvider;

/** ServiceLoader call packaged in the embedded consumer JAR. */
public final class EmbeddedConsumer
{
  private EmbeddedConsumer()
  {
  }

  public static String firstProviderMessage()
  {
    return ServiceLoader.load(SPIProvider.class)
        .findFirst()
        .map(SPIProvider::getMessage)
        .orElse(null);
  }
}
