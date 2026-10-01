/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServicePermission;
import org.osgi.framework.ServiceRegistration;
import java.util.*;

final class OsgiServiceEntry
{
  /*----------------------- classes and enums -------------------------*/

  /** Tracks one Provider implementation registered as an OSGi service. */
  static final class ServiceRegistrationInfo
  {
    private final String serviceType_;
    private final String serviceProvider_;
    final List<ServiceRegistration> registrations_ = new ArrayList<>();
    /**
         * @param serviceType the fully qualified name of the provided Service Type.
         * @param serviceProvider the fully qualified name of the Provider
         *                        implementation class.
         */
    ServiceRegistrationInfo(String serviceType, String serviceProvider)
    {
      serviceType_ = serviceType;
      serviceProvider_ = serviceProvider;
    }

    /**
     * Registers a discovered Service Provider as an OSGi service when required by
     * a matching {@code osgi.serviceloader} capability.
     *
     * <p>The method examines all supplied capabilities advertising the requested
     * Service Type and creates a registration for each matching capability whose
     * register mode selects the Provider implementation. The caller checks the
     * provider's registrar extender wire before invoking this method.</p>
     *
     * @param bundle the bundle containing the Service Provider implementation.
     */
    void registerService(Bundle bundle,
                         Bundle spiBundle,
                         List<ProviderCapability> provideCapabilities)
    {
      if (!registrations_.isEmpty())
        {
          return;
        }
      for (ProviderCapability capability : provideCapabilities)
        {
          if (!capability.getServiceType().equals(serviceType_))
            {
              continue;
            }
          if (capability.getRegisterMode() == ProviderCapability.RegisterMode.ALL ||
              (capability.getRegisterMode() == ProviderCapability.RegisterMode.SINGLE &&
               serviceProvider_.equals(capability.getSelectedProvider())))
            {
              ServiceRegistration registration =
                  registerOsgiService(bundle, spiBundle, capability.getAttributes());
              if (registration != null)
                {
                  registrations_.add(registration);
                }
            }
        }
    }

    /**
     * Registers this object's Provider implementation as an OSGi service.
     *
     * <p>When a {@link SecurityManager} is active, the Provider bundle must have
     * {@link ServicePermission#REGISTER} permission for the specified Service Type.
     * If the permission is missing, no service is registered.</p>
     *
     * @param bundle the bundle containing the Provider implementation and in whose
     *               bundle context the service is registered.
     * @param spiBundle the Service Loader mediator bundle.
     * @param properties the service properties to associate with the registration.
     *
     * @return the created OSGi service registration, or {@code null} if registration fails
     */
    private ServiceRegistration registerOsgiService(Bundle bundle,
                                                    Bundle spiBundle,
                                                    Hashtable<String, Object> properties)
    {
      BundleContext context = bundle.getBundleContext();
      if (context == null)
        {
          return null;
        }
      try
        {
          Class<?> providerClass = bundle.loadClass(serviceProvider_);
          Object instance = new ProviderServiceFactory(providerClass);
          Hashtable<String, Object> registrationProperties =
            new Hashtable<>(properties);
          registrationProperties.put(MediatorConstants.SERVICELOADER_MEDIATOR_PROPERTY,
                                     spiBundle.getBundleId());

          if (System.getSecurityManager() != null &&
              !bundle.hasPermission(new ServicePermission(serviceType_,
                                                          ServicePermission.REGISTER)))
            {
              MediatorActivator.logger_.warn("Does not have the permission to register services of type: " +
                                             serviceType_);
              return null;
            }
          ServiceRegistration registration = context.registerService(serviceType_,
                                                                     instance,
                                                                     registrationProperties);
          MediatorActivator.printDebug("[PROVIDER_TRACKER] register OSGI Service " +
                                       serviceType_ +
                                       " - " + serviceProvider_ + " " +
                                       registrationProperties);
          return registration;
        }
      catch (ClassNotFoundException e)
        {
          MediatorActivator.logger_.warn("Could not load provider " +
                                         serviceProvider_ + " of service " +
                                         serviceType_, e);
          return null;
        }
    }

    void unregister()
    {
      for (ServiceRegistration registration : registrations_)
        {
          try
            {
              MediatorActivator.printDebug("[PROVIDER_TRACKER] unregister OSGI Service " +
                                           registration);
              registration.unregister();
            }
          catch (IllegalStateException ignored)
            {
              // Cleanup must tolerate framework lifecycle races.
            }
        }
      registrations_.clear();
    }
  }

  /*--------------------------- variables -----------------------------*/

  private final Map<String, Set<String>> implementationsByType_;
  private final List<ProviderCapability> provideCapabilities_;

  private List<ServiceRegistrationInfo> serviceRegistrationInfos_;

  /*------------------------  constructors  ---------------------------*/

  OsgiServiceEntry(Map<String, Set<String>> impByType,
                   List<ProviderCapability> provideCapabilities)
  {
    Map<String, Set<String>> implementations = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> entry : impByType.entrySet())
      {
        implementations.put(entry.getKey(),
                            Collections.unmodifiableSet(
                                                        new LinkedHashSet<>(entry
                                                            .getValue())));
      }
    this.implementationsByType_ = Collections.unmodifiableMap(implementations);
    this.provideCapabilities_ = List.copyOf(provideCapabilities);
  }

  /*---------------------------- methods ------------------------------*/

  private List<ServiceRegistrationInfo> getServiceRegistrationInfos()
  {
    if (serviceRegistrationInfos_ == null)
      {
        serviceRegistrationInfos_ = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : implementationsByType_.entrySet())
          {
            String serviceType = entry.getKey();
            for (String provider : entry.getValue())
              {
                serviceRegistrationInfos_.add(new ServiceRegistrationInfo(serviceType,
                                                                          provider));
              }
          }
      }
    return serviceRegistrationInfos_;
  }

  void registerOsgiServices(Bundle bundle, Bundle spiBundle)
  {
    for (ServiceRegistrationInfo info : getServiceRegistrationInfos())
      {
        info.registerService(bundle, spiBundle, provideCapabilities_);
      }
  }

  void unregisterOsgiServices() {
    if (serviceRegistrationInfos_ == null) {
      return;
    }

    for (ServiceRegistrationInfo info : serviceRegistrationInfos_) {
      info.unregister();
    }
  }
}
