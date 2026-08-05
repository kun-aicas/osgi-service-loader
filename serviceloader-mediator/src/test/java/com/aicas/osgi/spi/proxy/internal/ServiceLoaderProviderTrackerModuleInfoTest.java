/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.service.log.Logger;

/** Tests provider discovery from {@code module-info.class}. */
public class ServiceLoaderProviderTrackerModuleInfoTest
{
  private static final String SERVICE_TYPE =
      "com.aicas.jamaica.ams.osgi.spi.test.module.Service";
  private static final String PROVIDER_ONE =
      "com.aicas.jamaica.ams.osgi.spi.test.module.ProviderOne";
  private static final String PROVIDER_TWO =
      "com.aicas.jamaica.ams.osgi.spi.test.module.ProviderTwo";

  private Logger previousLogger;

  @Before
  public void setUpLogger()
  {
    previousLogger = MediatorActivator.logger_;
    MediatorActivator.logger_ = mock(Logger.class);
  }


  public void tearDown()
  {
    MediatorActivator.logger_ = previousLogger;
  }

  @Test
  public void registersAllProvidersDeclaredByModuleInfo() throws Exception
  {
    URL moduleInfo = moduleInfoResource();
    Bundle providerBundle = mock(Bundle.class);
    Bundle mediatorBundle = mock(Bundle.class);
    BundleRevision revision = mock(BundleRevision.class);
    MediatorActivator mediator = new MediatorActivator();

    when(providerBundle.adapt(BundleRevision.class)).thenReturn(revision);
    when(revision.getTypes()).thenReturn(0);
    when(revision.getDeclaredCapabilities(
        MediatorConstants.SERVICELOADER_CAPABILITY_NAMESPACE))
        .thenReturn(Collections.emptyList());
    when(revision.getDeclaredRequirements(
        MediatorConstants.EXTENDER_CAPABILITY_NAMESPACE))
        .thenReturn(Collections.emptyList());
    when(providerBundle.getEntry(MediatorConstants.MODULE_INFO))
        .thenReturn(moduleInfo);
    when(providerBundle.getBundleId()).thenReturn(73L);

    ServiceLoaderProviderTracker tracker =
        new ServiceLoaderProviderTracker(mediator, mediatorBundle);

    assertNotNull(tracker.addingBundle(providerBundle, null));
    Map<Long, List<String>> providers = new HashMap<>();
    mediator.getRegisteredProviders(SERVICE_TYPE, providers);
    assertEquals(Collections.singletonMap(73L,
                                          Arrays.asList(PROVIDER_ONE,
                                                        PROVIDER_TWO)),
                 providers);
  }

  /**
   * Returns the module descriptor used by this test.
   *
   * <p>The {@code module-info.java} fixture declares the named module
   * {@code test.providers}, exports
   * {@code com.aicas.jamaica.ams.osgi.spi.test.module}, and declares two
   * providers for
   * {@code com.aicas.jamaica.ams.osgi.spi.test.module.Service}:</p>
   *
   * <pre>{@code
   * module test.providers {
   *   exports com.aicas.jamaica.ams.osgi.spi.test.module;
   *   provides com.aicas.jamaica.ams.osgi.spi.test.module.Service
   *       with com.aicas.jamaica.ams.osgi.spi.test.module.ProviderOne,
   *            com.aicas.jamaica.ams.osgi.spi.test.module.ProviderTwo;
   * }
   * }</pre>
   *
   * <p>The compiled descriptor is stored at
   * {@code src/test/resources/moduleInfoTest/module-info.class}; this
   * source-form documentation records exactly what the binary fixture
   * contains.</p>
   *
   * @return the {@code module-info.class} resource as a bundle-entry URL
   * @throws IOException if the descriptor resource is missing
   */
  private URL moduleInfoResource() throws IOException
  {
    URL resource = ServiceLoaderProviderTrackerModuleInfoTest.class
        .getClassLoader()
        .getResource("moduleInfoTest/module-info.class");
    if (resource == null)
      {
        throw new IOException("Test resource not found: moduleInfoTest/module-info.class");
      }
    return resource;
  }
}
