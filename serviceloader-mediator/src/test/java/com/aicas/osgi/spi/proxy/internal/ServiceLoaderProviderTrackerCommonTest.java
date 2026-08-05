/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.service.log.Logger;

import com.aicas.osgi.spi.proxy.internal.ServiceLoaderProviderTracker.ProviderBundleInfo;
import com.aicas.osgi.spi.proxy.internal.ServiceLoaderProviderTracker.ServiceRegistrationInfo;

/** Unit tests for common {@link ServiceLoaderProviderTracker} behavior. */
public class ServiceLoaderProviderTrackerCommonTest
{
  private static final String SERVICE_TYPE =
      "com.aicas.osgi.spi.proxy.ServiceLoaderTest$TestService";
  private static final String PROVIDER_TYPE = "example.Provider";
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

  @Test
  public void ignoresTheMediatorBundle()
  {
    Bundle mediatorBundle = mock(Bundle.class);
    ServiceLoaderProviderTracker tracker =
        new ServiceLoaderProviderTracker(new MediatorActivator(), mediatorBundle);

    assertNull(tracker.addingBundle(mediatorBundle, null));
  }

  @Test
  public void ignoresFragmentBundles()
  {
    Bundle bundle = mock(Bundle.class);
    Bundle mediatorBundle = mock(Bundle.class);
    BundleRevision revision = mock(BundleRevision.class);
    when(bundle.adapt(BundleRevision.class)).thenReturn(revision);
    when(revision.getTypes()).thenReturn(BundleRevision.TYPE_FRAGMENT);

    ServiceLoaderProviderTracker tracker =
        new ServiceLoaderProviderTracker(new MediatorActivator(), mediatorBundle);

    assertNull(tracker.addingBundle(bundle, null));
  }

  @Test
  public void tracksBundleWithoutProviderMetadata()
  {
    MediatorActivator activator = new MediatorActivator();
    Bundle bundle = mock(Bundle.class);
    Bundle mediatorBundle = mock(Bundle.class);
    BundleRevision revision = mock(BundleRevision.class);
    when(bundle.adapt(BundleRevision.class)).thenReturn(revision);
    when(bundle.getEntry(MediatorConstants.MODULE_INFO)).thenReturn(null);

    ServiceLoaderProviderTracker tracker =
        new ServiceLoaderProviderTracker(activator, mediatorBundle);

    assertNotNull(tracker.addingBundle(bundle, null));
    assertFalse(activator.unregisterProviderBundle(bundle));
  }

  /** Verifies that tracked OSGi registrations are removed with the bundle. */
  @Test
  public void removesOsgiRegistrationsWhenBundleIsRemoved()
  {
    Bundle bundle = mock(Bundle.class);
    ServiceRegistration<?> registration = mock(ServiceRegistration.class);
    ServiceRegistrationInfo registrationInfo =
        new ServiceRegistrationInfo(SERVICE_TYPE,
                                    PROVIDER_TYPE);
    registrationInfo.registrations_.add(registration);
    ProviderBundleInfo info =
        new ProviderBundleInfo(java.util.Collections.emptyList(),
                               java.util.Collections.singletonList(registrationInfo),
                               java.util.Collections.emptyList());

    ServiceLoaderProviderTracker tracker =
        new ServiceLoaderProviderTracker(new MediatorActivator(), mock(Bundle.class));

    tracker.removedBundle(bundle, null, info);

    verify(registration).unregister();
    assertTrue(registrationInfo.registrations_.isEmpty());
  }
}
