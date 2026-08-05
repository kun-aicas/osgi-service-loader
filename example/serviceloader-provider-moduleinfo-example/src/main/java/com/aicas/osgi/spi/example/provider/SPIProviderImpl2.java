package com.aicas.osgi.spi.example.provider;

import com.aicas.osgi.spi.example.spi.SPIProvider;


public class SPIProviderImpl2 implements SPIProvider
{
  @Override
  public String getMessage() {
          return "Hi, I was declared via module-info.java.";
  }
}
