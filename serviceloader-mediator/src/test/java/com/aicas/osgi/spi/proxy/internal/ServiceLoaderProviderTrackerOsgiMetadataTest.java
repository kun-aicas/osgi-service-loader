/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.log.Logger;

import com.aicas.osgi.spi.proxy.internal.ServiceLoaderProviderTracker.ProviderBundleInfo;
import com.aicas.osgi.spi.proxy.internal.ServiceLoaderProviderTracker.RegisterMode;

/** Tests analysis of OSGi Service Loader metadata. */
public class ServiceLoaderProviderTrackerOsgiMetadataTest
{
  private static final String SERVICE_TYPE =
      OsgiTestService.class.getName();
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
   * Simulates a provider bundle with the following metadata:
   *
   * <pre>{@code
   * Provide-Capability:
   *   osgi.serviceloader;
   *     osgi.serviceloader="...ServiceLoaderProviderTrackerOsgiMetadataTest$OsgiTestService"
   * Require-Capability: none
   * }
   * </pre>
   *
   * Its {@code META-INF/services/<service-type>} file contains
   * {@code OsgiTestProvider}. Since the registrar extender requirement is not
   * present, the provider is registered only in the mediator and no OSGi
   * service registration is created.
   */
  @Test
  public void analyzesOsgiMetadataAndRegistersProvidersInMediator() throws Exception
  {
    Bundle providerBundle = mock(Bundle.class);
    Bundle mediatorBundle = mock(Bundle.class);
    BundleRevision revision = mock(BundleRevision.class);
    BundleCapability capability = mock(BundleCapability.class);
    MediatorActivator mediator = new MediatorActivator();

    when(providerBundle.adapt(BundleRevision.class)).thenReturn(revision);
    when(providerBundle.getState()).thenReturn(Bundle.RESOLVED);
    when(providerBundle.getResources(MediatorConstants.METAINF_SERVICES + "/" + SERVICE_TYPE))
        .thenReturn(Collections.enumeration(Collections.singleton(
            getClass().getClassLoader().getResource("META-INF/services/" + SERVICE_TYPE))));
    when(revision.getTypes()).thenReturn(0);
    when(revision.getDeclaredCapabilities(MediatorConstants.SERVICELOADER_CAPABILITY_NAMESPACE))
        .thenReturn(Collections.singletonList(capability));
    when(revision.getDeclaredRequirements(MediatorConstants.EXTENDER_CAPABILITY_NAMESPACE))
        .thenReturn(Collections.emptyList());
    when(capability.getAttributes()).thenReturn(Collections.singletonMap(
        MediatorConstants.SERVICELOADER_CAPABILITY_NAMESPACE, SERVICE_TYPE));
    when(capability.getDirectives()).thenReturn(Collections.emptyMap());
    when(providerBundle.getBundleId()).thenReturn(17L);

    ServiceLoaderProviderTracker tracker =
        new ServiceLoaderProviderTracker(mediator, mediatorBundle);
    ProviderBundleInfo info = tracker.addingBundle(providerBundle, null);

    assertNotNull(info);
    Map<Long, List<String>> providers = new HashMap<>();
    mediator.getRegisteredProviders(SERVICE_TYPE, providers);
    assertEquals(Collections.singletonList(
                     OsgiTestProvider.class.getName()),
                 providers.get(17L));
  }

  /**
   * Simulates a provider bundle with this manifest metadata:
   *
   * <pre>{@code
   * Provide-Capability:
   *   osgi.serviceloader;
   *     osgi.serviceloader="...ServiceLoaderProviderTrackerOsgiMetadataTest$OsgiTestService";
   *     key="value"
   * Require-Capability:
   *   osgi.extender;
   *     filter:="(osgi.extender=osgi.serviceloader.registrar)"
   * }
   * </pre>
   *
   * The matching descriptor is:
   *
   * <pre>{@code
   * META-INF/services/...ServiceLoaderProviderTrackerOsgiMetadataTest$OsgiTestService:
   * ...ServiceLoaderProviderTrackerOsgiMetadataTest$OsgiTestProvider
   * }
   * </pre>
   *
   * With no {@code register} directive, Registrar processing uses
   * {@link RegisterMode#ALL}. The tracker must register the descriptor's
   * implementation with the mediator and as an OSGi service, preserving the
   * {@code key=value} capability property.
   */
  @Test
  public void registersProviderAsAnOsgiServiceForAllMode()
      throws Exception
  {
    Bundle providerBundle = mock(Bundle.class);
    Bundle mediatorBundle = mock(Bundle.class);
    BundleRevision revision = mock(BundleRevision.class);
    BundleCapability capability = mock(BundleCapability.class);
    BundleRequirement registrarRequirement = mock(BundleRequirement.class);
    BundleContext context = mock(BundleContext.class);
    ServiceRegistration<?> registration = mock(ServiceRegistration.class);
    MediatorActivator mediator = new MediatorActivator();
    String providerClassName =
        OsgiTestProvider.class.getName();

    when(providerBundle.adapt(BundleRevision.class)).thenReturn(revision);
    when(providerBundle.getState()).thenReturn(Bundle.ACTIVE);
    when(providerBundle.getResources(MediatorConstants.METAINF_SERVICES + "/" + SERVICE_TYPE))
        .thenReturn(Collections.enumeration(Collections.singleton(
            getClass().getClassLoader().getResource("META-INF/services/" + SERVICE_TYPE))));
    when(providerBundle.getBundleContext()).thenReturn(context);
    when(providerBundle.getBundleId()).thenReturn(42L);
    when(revision.getTypes()).thenReturn(0);
    when(revision.getDeclaredCapabilities(MediatorConstants.SERVICELOADER_CAPABILITY_NAMESPACE))
        .thenReturn(Collections.singletonList(capability));
    when(revision.getDeclaredRequirements(MediatorConstants.EXTENDER_CAPABILITY_NAMESPACE))
        .thenReturn(Collections.singletonList(registrarRequirement));
    when(registrarRequirement.getDirectives()).thenReturn(Collections.singletonMap(
        MediatorConstants.FILTER_DIRECTIVE,
        "(osgi.extender=osgi.serviceloader.registrar)"));
    configureRegistrarWire(revision, registrarRequirement, mediatorBundle);
    when(revision.getBundle()).thenReturn(providerBundle);
    when(capability.getAttributes()).thenReturn(Map.of(
        MediatorConstants.SERVICELOADER_CAPABILITY_NAMESPACE, SERVICE_TYPE,
        "key", "value"));
    when(capability.getDirectives()).thenReturn(Collections.emptyMap());
    when(mediatorBundle.getBundleId()).thenReturn(99L);
    when(providerBundle.getBundleContext()).thenReturn(context);
    try
      {
        doReturn(OsgiTestProvider.class).when(providerBundle).loadClass(providerClassName);
      }
    catch (ClassNotFoundException e)
      {
        throw new RuntimeException(e);
      }
    when(context.registerService(anyString(), any(), any(Hashtable.class)))
        .thenReturn(registration);

    ServiceLoaderProviderTracker tracker =
        new ServiceLoaderProviderTracker(mediator, mediatorBundle);
    ProviderBundleInfo info = tracker.addingBundle(providerBundle, null);

    // registered in the mediator.
    Map<Long, List<String>> providers = new HashMap<>();
    mediator.getRegisteredProviders(SERVICE_TYPE, providers);
    assertEquals(Collections.singletonList(providerClassName),
                 providers.get(42L));

    // registered as the osgi service.
    assertNotNull(info);
    ArgumentCaptor<Hashtable> propertiesCaptor =
        ArgumentCaptor.forClass(Hashtable.class);
    verify(context).registerService(eq(SERVICE_TYPE),
                                    isA(ProviderServiceFactory.class),
                                    propertiesCaptor.capture());
    Hashtable properties = propertiesCaptor.getValue();
    assertEquals("value", properties.get("key"));
    assertEquals(99L,
                  properties.get(MediatorConstants.SERVICELOADER_MEDIATOR_PROPERTY));
  }

  /**
   * Simulates this manifest metadata:
   *
   * <pre>{@code
   * Provide-Capability:
   *   osgi.serviceloader;
   *     osgi.serviceloader="...ServiceLoaderProviderTrackerOsgiMetadataTest$MultipleProviderService"
   * Require-Capability:
   *   osgi.extender;
   *     filter:="(osgi.extender=osgi.serviceloader.registrar)"
   * }
   * </pre>
   *
   * Its one descriptor declares two implementations:
   *
   * <pre>{@code
   * META-INF/services/...ServiceLoaderProviderTrackerOsgiMetadataTest$MultipleProviderService:
   * ...ServiceLoaderProviderTrackerOsgiMetadataTest$FirstMultipleProvider
   * ...ServiceLoaderProviderTrackerOsgiMetadataTest$SecondMultipleProvider
   * }
   * </pre>
   *
   * Because the capability has no {@code register} directive, it uses
   * {@link RegisterMode#ALL}; both implementations must be registered with the
   * mediator and the OSGi service registry.
   */
  @Test
  public void registersEveryProviderListedInOneDescriptorForAllMode()
      throws Exception
  {
    Bundle providerBundle = mock(Bundle.class);
    Bundle mediatorBundle = mock(Bundle.class);
    BundleRevision revision = mock(BundleRevision.class);
    BundleCapability capability = mock(BundleCapability.class);
    BundleRequirement registrarRequirement = mock(BundleRequirement.class);
    BundleContext context = mock(BundleContext.class);
    MediatorActivator mediator = new MediatorActivator();
    String serviceType = MultipleProviderService.class.getName();

    when(providerBundle.adapt(BundleRevision.class)).thenReturn(revision);
    when(providerBundle.getState()).thenReturn(Bundle.ACTIVE);
    when(providerBundle.getResources(MediatorConstants.METAINF_SERVICES + "/" + serviceType))
        .thenReturn(Collections.enumeration(Collections.singleton(
            getClass().getClassLoader().getResource("META-INF/services/" + serviceType))));
    when(providerBundle.getBundleContext()).thenReturn(context);
    when(providerBundle.getBundleId()).thenReturn(43L);
    when(revision.getTypes()).thenReturn(0);
    when(revision.getDeclaredCapabilities(MediatorConstants.SERVICELOADER_CAPABILITY_NAMESPACE))
        .thenReturn(Collections.singletonList(capability));
    when(revision.getDeclaredRequirements(MediatorConstants.EXTENDER_CAPABILITY_NAMESPACE))
        .thenReturn(Collections.singletonList(registrarRequirement));
    when(registrarRequirement.getDirectives()).thenReturn(Collections.singletonMap(
        MediatorConstants.FILTER_DIRECTIVE,
        "(osgi.extender=osgi.serviceloader.registrar)"));
    configureRegistrarWire(revision, registrarRequirement, mediatorBundle);
    when(revision.getBundle()).thenReturn(providerBundle);
    when(capability.getAttributes()).thenReturn(Collections.singletonMap(
        MediatorConstants.SERVICELOADER_CAPABILITY_NAMESPACE, serviceType));
    when(capability.getDirectives()).thenReturn(Collections.emptyMap());
    when(mediatorBundle.getBundleId()).thenReturn(99L);
    try
      {
        doReturn(FirstMultipleProvider.class).when(providerBundle).loadClass(
            FirstMultipleProvider.class.getName());
        doReturn(SecondMultipleProvider.class).when(providerBundle).loadClass(
            SecondMultipleProvider.class.getName());
      }
    catch (ClassNotFoundException e)
      {
        throw new RuntimeException(e);
      }
    when(context.registerService(anyString(), any(), any(Hashtable.class)))
        .thenReturn(mock(ServiceRegistration.class));

    ServiceLoaderProviderTracker tracker =
        new ServiceLoaderProviderTracker(mediator, mediatorBundle);
    ProviderBundleInfo info = tracker.addingBundle(providerBundle, null);

    Map<Long, List<String>> providers = new HashMap<>();
    mediator.getRegisteredProviders(serviceType, providers);
    assertNotNull(info);
    assertEquals(Set.of(FirstMultipleProvider.class.getName(),
                        SecondMultipleProvider.class.getName()),
                 new HashSet<String>(providers.get(43L)));
    verify(context, times(2)).registerService(eq(serviceType),
                                              isA(ProviderServiceFactory.class),
                                              any(Hashtable.class));
  }

  /**
   * Simulates a bundle with no {@code osgi.serviceloader} capability and no
   * {@code module-info.class}, despite containing this descriptor:
   *
   * <pre>{@code
   * META-INF/services/...ServiceLoaderProviderTrackerOsgiMetadataTest$OsgiTestService:
   * ...ServiceLoaderProviderTrackerOsgiMetadataTest$OsgiTestProvider
   * }
   * </pre>
   *
   * A ServiceLoader descriptor alone does not opt a bundle into mediator
   * processing, so the descriptor must not be read or registered.
   */
  @Test
  public void ignoresDescriptorWithoutServiceLoaderCapability() throws Exception
  {
    Bundle providerBundle = mock(Bundle.class);
    Bundle mediatorBundle = mock(Bundle.class);
    BundleRevision revision = mock(BundleRevision.class);
    MediatorActivator mediator = new MediatorActivator();

    when(providerBundle.adapt(BundleRevision.class)).thenReturn(revision);
    when(providerBundle.getState()).thenReturn(Bundle.RESOLVED);
    when(providerBundle.getResources(MediatorConstants.METAINF_SERVICES + "/" + SERVICE_TYPE))
        .thenReturn(Collections.enumeration(Collections.singleton(
            getClass().getClassLoader().getResource("META-INF/services/" + SERVICE_TYPE))));
    when(providerBundle.getBundleId()).thenReturn(44L);
    when(revision.getTypes()).thenReturn(0);
    when(revision.getDeclaredCapabilities(MediatorConstants.SERVICELOADER_CAPABILITY_NAMESPACE))
        .thenReturn(Collections.emptyList());
    when(revision.getDeclaredRequirements(MediatorConstants.EXTENDER_CAPABILITY_NAMESPACE))
        .thenReturn(Collections.emptyList());
    when(providerBundle.getEntry(MediatorConstants.MODULE_INFO)).thenReturn(null);

    ServiceLoaderProviderTracker tracker =
        new ServiceLoaderProviderTracker(mediator, mediatorBundle);
    ProviderBundleInfo info = tracker.addingBundle(providerBundle, null);

    assertNotNull(info);
    Map<Long, List<String>> providers = new HashMap<>();
    mediator.getRegisteredProviders(SERVICE_TYPE, providers);
    assertNull(providers.get(44L));
  }

  public interface OsgiTestService
  {
  }

  private void configureRegistrarWire(BundleRevision revision,
                                      BundleRequirement requirement,
                                      Bundle mediatorBundle)
  {
    BundleWiring requirerWiring = mock(BundleWiring.class);
    BundleWiring providerWiring = mock(BundleWiring.class);
    BundleWire wire = mock(BundleWire.class);
    BundleCapability capability = mock(BundleCapability.class);
    when(revision.getWiring()).thenReturn(requirerWiring);
    when(requirerWiring.getRequiredWires(MediatorConstants.EXTENDER_CAPABILITY_NAMESPACE))
        .thenReturn(Collections.singletonList(wire));
    when(wire.getRequirement()).thenReturn(requirement);
    when(wire.getProviderWiring()).thenReturn(providerWiring);
    when(providerWiring.getBundle()).thenReturn(mediatorBundle);
    when(wire.getCapability()).thenReturn(capability);
    when(capability.getAttributes()).thenReturn(Map.of(
        MediatorConstants.EXTENDER_CAPABILITY_NAMESPACE,
        MediatorConstants.REGISTRAR_EXTENDER_NAME,
        MediatorConstants.VERSION_ATTRIBUTE,
        MediatorConstants.SPECIFICATION_VERSION));
  }

  public static final class OsgiTestProvider implements OsgiTestService
  {
    public OsgiTestProvider()
    {
    }
  }

  public interface MultipleProviderService
  {
  }

  public static final class FirstMultipleProvider implements MultipleProviderService
  {
  }

  public static final class SecondMultipleProvider implements MultipleProviderService
  {
  }
}
