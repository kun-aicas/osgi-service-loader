/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceRegistration;

public class ProviderServiceFactory implements ServiceFactory
{

  private final Class<?> providerClass_;

  public ProviderServiceFactory(Class<?> cls) {
    providerClass_ = cls;
}

  @Override
  public Object getService(Bundle bundle, ServiceRegistration registration)
  {
    MediatorActivator.printDebug("[ProviderServiceFactory] provide bundle(" +
                                 bundle.getBundleId() +
                                 ") for service " + providerClass_.getName());
    try
      {
        return providerClass_.getDeclaredConstructor().newInstance();
      }
    catch (Exception e)
      {
        throw new RuntimeException("Unable to instantiate class " +
                providerClass_ + " Does it have a public no-arg constructor?",
                                   e);
      }
  }

  @Override
  public void ungetService(Bundle bundle, ServiceRegistration registration,
                           Object service)
  {
    // TODO Auto-generated method stub
  }
}
