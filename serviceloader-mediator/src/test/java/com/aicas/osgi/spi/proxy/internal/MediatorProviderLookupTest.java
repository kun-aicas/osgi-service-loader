/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

/** Tests provider lookup after consumer visibility and package filtering. */
public class MediatorProviderLookupTest
{
  private static final String SERVICE_TYPE = "com.example.Codec";

  @Test
  public void restrictedConsumerSeesOnlyItsWiredProvider()
  {
    MediatorActivator mediator = new MediatorActivator();
    BundleCapability servicePackage = packageCapability();
    Bundle consumer = bundleImportingPackage(servicePackage);
    Bundle allowedProvider = provider(1L);
    Bundle excludedProvider = provider(2L);

    mediator.registerConsumerBundle(consumer,
                                    ConsumerVisibility.restricted(Map.of(SERVICE_TYPE,
                                                                         Set.of(1L))));
    registerProvider(mediator, allowedProvider, servicePackage,
                     "example.AllowedCodec");
    registerProvider(mediator, excludedProvider, servicePackage,
                     "example.ExcludedCodec");

    Map<Long, Set<String>> providers = new HashMap<>();
    mediator.getProviders(SERVICE_TYPE, consumer, providers);

    assertEquals(Map.of(1L, Set.of("example.AllowedCodec")), providers);
  }

  @Test
  public void metadataFreeConsumerSeesEveryCompatibleProvider()
  {
    MediatorActivator mediator = new MediatorActivator();
    BundleCapability servicePackage = packageCapability();
    Bundle consumer = bundleImportingPackage(servicePackage);
    Bundle firstProvider = provider(1L);
    Bundle secondProvider = provider(2L);

    registerProvider(mediator, firstProvider, servicePackage,
                     "example.FirstCodec");
    registerProvider(mediator, secondProvider, servicePackage,
                     "example.SecondCodec");

    Map<Long, Set<String>> providers = new HashMap<>();
    mediator.getProviders(SERVICE_TYPE, consumer, providers);

    assertEquals(Map.of(1L, Set.of("example.FirstCodec"),
                        2L, Set.of("example.SecondCodec")), providers);
  }

  @Test
  public void metadataFreeConsumerDoesNotDiscoverProviderWithoutPackageSource()
  {
    MediatorActivator mediator = new MediatorActivator();
    Bundle consumer = mock(Bundle.class);
    Bundle provider = provider(1L);

    mediator.registerServiceProviderEntries(provider,
                                            new ProviderEntry(SERVICE_TYPE,
                                                              provider,
                                                              null,
                                                              Set.of("example.UnwiredCodec")));

    assertTrue(providersFor(mediator, consumer).isEmpty());
  }

  @Test
  public void resolvedDynamicProviderCapabilityIsCached()
    throws Exception
  {
    MediatorActivator mediator = new MediatorActivator();
    BundleCapability servicePackage = packageCapability();
    Bundle consumer = bundleImportingPackage(servicePackage);
    Bundle provider = provider(1L);
    BundleWiring providerWiring = mock(BundleWiring.class);
    BundleWire providerWire = mock(BundleWire.class);
    AtomicBoolean dynamicallyResolved = new AtomicBoolean();

    when(provider.adapt(BundleWiring.class)).thenReturn(providerWiring);

    when(providerWiring.getRequiredWires(BundleRevision.PACKAGE_NAMESPACE)).
         thenAnswer(ignored -> dynamicallyResolved.get() ? List.of(providerWire)
                                                         : List.of());

    when(providerWiring.getCapabilities(BundleRevision.PACKAGE_NAMESPACE)).
         thenReturn(List.of());
    when(providerWire.getCapability()).thenReturn(servicePackage);
    doAnswer(ignored ->
              {
                dynamicallyResolved.set(true);
                return Object.class;
              }).when(provider).loadClass(SERVICE_TYPE);
    mediator.registerServiceProviderEntries(provider,
                                            new ProviderEntry(SERVICE_TYPE,
                                                              provider,
                                                              null,
                                                              Set.of("example.DynamicCodec")));

    //The first lookup resolves and discovers the provider.
    assertEquals(Map.of(1L, Set.of("example.DynamicCodec")),
                 providersFor(mediator, consumer));
    //Resolution loads the service type once.
    verify(provider, times(1)).loadClass(SERVICE_TYPE);

    //The second lookup still discovers the provider. It reuses the cached capability
    // without another class load.
    assertEquals(Map.of(1L, Set.of("example.DynamicCodec")),
                 providersFor(mediator, consumer));
    verify(provider, times(1)).loadClass(SERVICE_TYPE);
  }

  @Test
  public void javaPlatformServiceTypeDoesNotRequireAPackageWire()
    throws Exception
  {
    MediatorActivator mediator = new MediatorActivator();
    Bundle consumer = mock(Bundle.class);
    Bundle provider = provider(1L);
    String serviceType = java.util.function.Supplier.class.getName();

    mediator.registerServiceProviderEntries(provider,
                                            new ProviderEntry(serviceType,
                                                              provider,
                                                              null,
                                                              Set.of("example.PlatformSupplier")));

    Map<Long, Set<String>> providers = new HashMap<>();
    mediator.getProviders(serviceType, consumer, providers);

    assertEquals(Map.of(1L, Set.of("example.PlatformSupplier")), providers);
    verify(provider, never()).loadClass(serviceType);
  }

  private static Map<Long, Set<String>> providersFor(MediatorActivator mediator,
                                                     Bundle consumer)
  {
    Map<Long, Set<String>> providers = new HashMap<>();
    mediator.getProviders(SERVICE_TYPE, consumer, providers);
    return providers;
  }

  private static Bundle bundleImportingPackage(BundleCapability capability)
  {
    Bundle bundle = mock(Bundle.class);
    BundleWiring wiring = mock(BundleWiring.class);
    BundleWire wire = mock(BundleWire.class);
    when(bundle.adapt(BundleWiring.class)).thenReturn(wiring);
    when(wiring.getRequiredWires(BundleRevision.PACKAGE_NAMESPACE)).
        thenReturn(List.of(wire));
    when(wiring.getCapabilities(BundleRevision.PACKAGE_NAMESPACE)).
        thenReturn(List.of());
    when(wire.getCapability()).thenReturn(capability);
    return bundle;
  }

  private static BundleCapability packageCapability()
  {
    BundleCapability capability = mock(BundleCapability.class);
    when(capability.getAttributes()).
         thenReturn(Map.of(BundleRevision.PACKAGE_NAMESPACE, "com.example"));
    return capability;
  }

  private static Bundle provider(long bundleId)
  {
    Bundle bundle = mock(Bundle.class);
    when(bundle.getBundleId()).thenReturn(bundleId);
    return bundle;
  }

  private static void registerProvider(MediatorActivator mediator,
                                       Bundle bundle,
                                       BundleCapability packageCapability,
                                       String implementationClass)
  {
    mediator.registerServiceProviderEntries(bundle,
                                            new ProviderEntry(SERVICE_TYPE,
                                                              bundle,
                                                              packageCapability,
                                                              Set.of(implementationClass)));
    mediator.registerServiceproviderCapabilities(bundle,
                                                 new ProviderCapability(SERVICE_TYPE,
                                                                        mock(BundleCapability.class),
                                                                        bundle));
  }
}
