package com.aicas.osgi.spi.example.provider;

import com.aicas.osgi.spi.example.spi.SPIProvider;

public class SPIProviderImpl implements SPIProvider
{
  @Override
  public String getMessage() {
          return "Hello, I was registered via OSGi metadata.";
  }
}
