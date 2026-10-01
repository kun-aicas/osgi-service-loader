/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.osgi.framework.Bundle;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.log.Logger;

/**
 * Tests collection of Service Loader provider declarations from bundle
 * resources.
 */
public class ServiceLoaderProviderTrackerTest
{
  private static final String MODULE_SERVICE_TYPE =
    "com.aicas.jamaica.ams.osgi.spi.test.module.Service";

  private static final Set<String> MODULE_PROVIDERS =
    Set.of("com.aicas.jamaica.ams.osgi.spi.test.module.ProviderOne",
           "com.aicas.jamaica.ams.osgi.spi.test.module.ProviderTwo");

  private static final String SERVICES_SERVICE_TYPE =
    "com.example.services.MetaInfService";

  private static final Set<String> SERVICES_PROVIDERS =
    Set.of("com.example.providers.AlphaProvider",
           "com.example.providers.BetaProvider");

  private Logger previousLogger;

  @Before
  public void setUpLogger()
  {
    previousLogger = MediatorActivator.logger_;
    MediatorActivator.logger_ = mock(Logger.class);
  }

  @After
  public void restoreLogger()
  {
    MediatorActivator.logger_ = previousLogger;
  }

  /**
   * Verifies that every {@code provides ... with ...} declaration in a real
   * {@code module-info.class} is collected into the mediator registry.
   */
  @Test
  public void collectsProvidersDeclaredByModuleInfo()
    throws Exception
  {
    Bundle providerBundle = providerBundle(73L, moduleInfoResource(),
                                           Collections.emptyEnumeration(),
                                           MODULE_SERVICE_TYPE);
    MediatorActivator mediator = mock(MediatorActivator.class);

    OsgiServiceEntry entry =
        new ServiceLoaderProviderTracker(mediator,
                                         mock(Bundle.class)).addingBundle(providerBundle,
                                                                          null);

    assertNotNull(entry);
    assertRegisteredProviders(mediator, providerBundle,
                              Map.of(MODULE_SERVICE_TYPE, MODULE_PROVIDERS));
  }

  /**
   * Verifies that a {@code META-INF/services} resource is parsed, including
   * comments, whitespace, and duplicate provider declarations.
   */
  @Test
  public void collectsProvidersDeclaredByMetaInfServices()
    throws Exception
  {
    Bundle providerBundle =
        providerBundle(74L,
                       null,
                       Collections.enumeration(List.of(serviceDescriptorResource())),
                       SERVICES_SERVICE_TYPE);
    MediatorActivator mediator = mock(MediatorActivator.class);

    OsgiServiceEntry entry =
        new ServiceLoaderProviderTracker(mediator, mock(Bundle.class)).
                                                   addingBundle(providerBundle,
                                                                null);

    assertNotNull(entry);
    assertRegisteredProviders(mediator, providerBundle,
                              Map.of(SERVICES_SERVICE_TYPE,
                                     SERVICES_PROVIDERS));
  }

  /**
   * Verifies that one bundle can contribute providers from both standard Java
   * metadata sources during a single scan.
   */
  @Test
  public void collectsProvidersFromModuleInfoAndMetaInfServices()
    throws Exception
  {
    Bundle providerBundle =
        providerBundle(75L,
                       moduleInfoResource(),
                       Collections.enumeration(List.of(serviceDescriptorResource())),
                       MODULE_SERVICE_TYPE,
                       SERVICES_SERVICE_TYPE);
    MediatorActivator mediator = mock(MediatorActivator.class);

    OsgiServiceEntry entry = new ServiceLoaderProviderTracker(mediator,
                                                              mock(Bundle.class))
                                                                  .addingBundle(providerBundle,
                                                                                null);

    assertNotNull(entry);
    assertRegisteredProviders(mediator, providerBundle,
                              Map.of(MODULE_SERVICE_TYPE, MODULE_PROVIDERS,
                                     SERVICES_SERVICE_TYPE,
                                     SERVICES_PROVIDERS));
  }

  /** Creates a host-bundle mock with provider scanning dependencies. */
  private static Bundle providerBundle(long bundleId,
                                       URL moduleInfo,
                                       Enumeration<URL> serviceFiles,
                                       String... serviceTypes)
  {
    Bundle bundle = mock(Bundle.class);
    BundleRevision revision = mock(BundleRevision.class);
    BundleWiring wiring = mock(BundleWiring.class);
    List<BundleCapability> packageCapabilities = new ArrayList<>();
    for (String serviceType : serviceTypes)
      {
        BundleCapability capability = mock(BundleCapability.class);
        when(capability.getAttributes()).thenReturn(Map.of(BundleRevision.PACKAGE_NAMESPACE,
                                                           PackageWiringUtil.packageOf(serviceType)));
        packageCapabilities.add(capability);
      }

    when(bundle.getBundleId()).thenReturn(bundleId);
    when(bundle.adapt(BundleRevision.class)).thenReturn(revision);
    when(bundle.adapt(BundleWiring.class)).thenReturn(wiring);
    when(bundle.getEntry(MediatorConstants.MODULE_INFO)).thenReturn(moduleInfo);
    when(bundle.findEntries(MediatorConstants.METAINF_SERVICES,
                            "*", false)).thenReturn(serviceFiles);
    when(revision.getTypes()).thenReturn(0);
    when(revision.getWiring()).thenReturn(wiring);
    when(revision.getDeclaredCapabilities(MediatorConstants.SERVICELOADER_CAPABILITY_NAMESPACE)).
                  thenReturn(List.of());
    when(wiring.getProvidedWires(HostNamespace.HOST_NAMESPACE)).
                thenReturn(List.of());
    when(wiring.getRequiredWires(BundleRevision.PACKAGE_NAMESPACE)).
                thenReturn(List.of());
    when(wiring.getCapabilities(BundleRevision.PACKAGE_NAMESPACE)).
                thenReturn(packageCapabilities);
    return bundle;
  }

  /** Asserts every ProviderEntry collected by the tracker. */
  private static void assertRegisteredProviders(MediatorActivator mediator,
                                                Bundle providerBundle,
                                                Map<String, Set<String>> expected)
  {
    ArgumentCaptor<ProviderEntry> entries =
      ArgumentCaptor.forClass(ProviderEntry.class);
    verify(mediator,
           times(expected.size())).registerServiceProviderEntries(eq(providerBundle),
                                                                  entries.capture());
    Map<String, Set<String>> providers = new java.util.HashMap<>();
    for (ProviderEntry entry : entries.getAllValues())
      {
        providers.put(entry.serviceType(), entry.implementationClasses());
      }

    assertEquals(expected, providers);
  }

  /** Returns the compiled module descriptor fixture used by these tests. */
  private static URL moduleInfoResource()
    throws IOException
  {
    return requiredResource("moduleInfoTest/module-info.class");
  }

  /** Returns the Service Provider configuration fixture used by these tests. */
  private static URL serviceDescriptorResource()
    throws IOException
  {
    return requiredResource("META-INF/services/" + SERVICES_SERVICE_TYPE);
  }

  private static URL requiredResource(String resourceName)
    throws IOException
  {
    URL resource = ServiceLoaderProviderTrackerTest.class.
                   getClassLoader().getResource(resourceName);
    if (resource == null)
      {
        throw new IOException("Test resource not found: " + resourceName);
      }
    return resource;
  }
}
