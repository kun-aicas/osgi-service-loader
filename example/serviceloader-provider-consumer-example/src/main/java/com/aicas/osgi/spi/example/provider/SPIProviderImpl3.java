package com.aicas.osgi.spi.example.provider;

import com.aicas.osgi.spi.example.spi.SPIProvider;

public class SPIProviderImpl3 implements SPIProvider
{
  @Override
  public String getMessage()
  {
    return "Hello, I was provided and consumed by the same bundle via module-info.java.";
  }
}
