/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.itests.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;

import com.aicas.osgi.spi.itests.support.AbstractIntegrationTest;
import com.aicas.osgi.spi.example.spi.SPIProvider;

/** Verifies a provider declared through OSGi ServiceLoader metadata. */
public class MetadataProviderIntegrationTest
    extends AbstractIntegrationTest
{
  /**
   * Verifies that the registrar publishes the metadata provider as an OSGi
   * service and that an OSGi client can obtain it from the service registry.
   *
   * <p>After the provider stops, the final assertion waits for its
   * {@link SPIProvider} service registration to be removed. This verifies that
   * the mediator unregisters the OSGi service during provider shutdown.</p>
   */
  @Test
  public void exposesTheMetadataProviderThroughTheOsgiServiceRegistry()
      throws Exception
  {
    Bundle provider = install("provider", "serviceloader-provider-example");
    Bundle osgiConsumer = install("osgi-consumer", "serviceloader-osgi-client-example");

    provider.start();
    osgiConsumer.start();

    ServiceReference<?> reference = awaitService(SPIProvider.class.getName(), true);
    assertNotNull(reference);
    assertEquals(SPIProvider.class.getName(),
                 ((String[])reference.getProperty(Constants.OBJECTCLASS))[0]);
    assertEquals(Bundle.ACTIVE, osgiConsumer.getState());
    assertOutputContains(REGULAR_PROVIDER_MESSAGE,
                         "[osgi client] - " + REGULAR_PROVIDER_MESSAGE);

    osgiConsumer.stop();
    provider.stop();

    assertNull(awaitService(SPIProvider.class.getName(), false));
    assertOutputContains("[osgi client] Stopped");
  }

  /**
   * Verifies that restarting a metadata provider removes its old OSGi service
   * registration and creates a new one while the OSGi client is running.
   */
  @Test
  public void recreatesTheOsgiServiceAfterProviderRestart()
      throws Exception
  {
    Bundle provider = install("provider", "serviceloader-provider-example");
    Bundle osgiConsumer = install("osgi-consumer", "serviceloader-osgi-client-example");

    provider.start();
    osgiConsumer.start();

    ServiceReference<?> firstReference =
        awaitService(SPIProvider.class.getName(), true);
    assertNotNull(firstReference);
    awaitOutputContains("[osgi client] - " + REGULAR_PROVIDER_MESSAGE);

    provider.stop();
    assertNull(awaitService(SPIProvider.class.getName(), false));

    provider.start();
    ServiceReference<?> restartedReference =
        awaitService(SPIProvider.class.getName(), true);
    assertNotNull(restartedReference);
    assertNotSame(firstReference, restartedReference);
    assertEquals(SPIProvider.class.getName(),
                 ((String[])restartedReference.getProperty(Constants.OBJECTCLASS))[0]);

    int restartOutputOffset = outputLength();
    osgiConsumer.stop();
    osgiConsumer.start();
    awaitOutputContainsAfter(restartOutputOffset,
                              "[osgi client] - " + REGULAR_PROVIDER_MESSAGE);

    osgiConsumer.stop();
    provider.stop();

    assertNull(awaitService(SPIProvider.class.getName(), false));
    assertOutputContains("[osgi client] Stopped");
  }

  /**
   * Verifies that the mediator exposes a metadata-declared provider to a woven
   * {@link java.util.ServiceLoader} consumer.
   *
   * <p>Stopping the provider must also remove its OSGi registration, even
   * though this scenario consumes the provider through ServiceLoader.</p>
   */
  @Test
  public void exposesTheMetadataProviderThroughServiceLoader() throws Exception
  {
    Bundle provider = install("provider", "serviceloader-provider-example");
    Bundle serviceLoaderConsumer = install("service-loader-consumer",
                                           "serviceloader-consumer-example");

    provider.start();
    serviceLoaderConsumer.start();

    assertEquals(Bundle.ACTIVE, serviceLoaderConsumer.getState());
    assertOutputContains("[consumer] Get all the registered services:/",
                         REGULAR_PROVIDER_MESSAGE);

    serviceLoaderConsumer.stop();
    provider.stop();

    assertNull(awaitService(SPIProvider.class.getName(), false));
    assertOutputContains("[consumer] stopped");
  }

  /**
   * Verifies a provider whose Service Loader declaration is split between a
   * fragment and its host bundle.
   *
   * <p>The fragment contributes the {@code osgi.serviceloader} provider
   * capability, while the host contributes the provider implementation and
   * its {@code META-INF/services/} configuration. The host does not declare a
   * registrar extender capability, so the mediator must make the provider
   * available to a {@link java.util.ServiceLoader} consumer without
   * registering it as an OSGi service.</p>
   *
   * <p>The test therefore expects the Service Loader consumer to find the
   * provider and the OSGi client to report that the
   * {@link SPIProvider} service is not found.</p>
   */
  @Test
  public void providerFragmentContributesServiceLoaderProviderMetadata()
      throws Exception
  {
    Bundle fragment = install("provider-fragment",
                              "serviceloader-provider-fragment-example");
    Bundle host = install("fragment-provider-host",
                          "serviceloader-provider-fragment-host-example");
    Bundle consumer = install("service-loader-consumer",
                              "serviceloader-consumer-example");
    Bundle osgiConsumer = install("osgi-consumer",
                                  "serviceloader-osgi-client-example");

    host.start();
    // This provider has no registrar extender and must not be published as
    // an OSGi service.
    assertNull(awaitService(SPIProvider.class.getName(), false));
    osgiConsumer.start();
    awaitOutputContains("[osgi client] SPIProvider service not found");

    // The consumer's result is the behavioral proof that ServiceLoader found
    // and instantiated the provider.
    consumer.start();
    awaitOutputContains("[consumer] Get all the registered services:/",
                        FRAGMENT_PROVIDER_MESSAGE);

    BundleRevision fragmentRevision = fragment.adapt(BundleRevision.class);
    java.util.List<BundleCapability> capabilities =
        fragmentRevision.getDeclaredCapabilities("osgi.serviceloader");
    assertEquals(1, capabilities.size());
    assertEquals(Bundle.RESOLVED, fragment.getState());

    osgiConsumer.stop();
    consumer.stop();
    host.stop();
    assertOutputContains("[consumer] stopped", "[osgi client] Stopped");
  }

  /**
   * Verifies that removing an attached provider fragment removes its provider
   * metadata from the host bundle after the host wiring is refreshed.
   */
  @Test
  public void removesProviderWhenFragmentIsUninstalled()
      throws Exception
  {
    Bundle fragment = install("provider-fragment",
                              "serviceloader-provider-fragment-example");
    Bundle host = install("fragment-provider-host",
                          "serviceloader-provider-fragment-host-example");
    Bundle consumer = install("service-loader-consumer",
                              "serviceloader-consumer-example");

    host.start();
    consumer.start();
    awaitOutputContains(FRAGMENT_PROVIDER_MESSAGE);
    assertEquals(Bundle.ACTIVE, host.getState());

    consumer.stop();
    int removalOutputOffset = outputLength();
    fragment.uninstall();
    refreshBundles(host);

    consumer.start();
    awaitOutputContainsAfter(removalOutputOffset,
                              "[consumer] No provider found.");

    consumer.stop();
    host.stop();

    assertOutputContains("[consumer] stopped");
  }

  /**
   * Verifies that host and fragment provider capabilities for different
   * service types are both retained.
   *
   * <p>The host declares {@link SPIProvider}, while its fragment declares
   * {@link Runnable}. Both service configuration files and the shared provider
   * implementation are in the host. With the registrar enabled on the host,
   * mediator-created OSGi registrations prove that neither capability was lost
   * while reading the combined host-and-fragment metadata.</p>
   */
  @Test
  public void retainsHostAndFragmentProviderCapabilities()
      throws Exception
  {
    Bundle fragment = installTestBundle("two-service-provider-fragment",
        "serviceloader-two-service-provider-fragment");
    Bundle host = installTestBundle("two-service-provider-host",
        "serviceloader-two-service-provider-host");

    host.start();

    assertNotNull(awaitService(SPIProvider.class.getName(), true));
    assertNotNull(awaitService(Runnable.class.getName(), true));
    assertEquals(Bundle.RESOLVED, fragment.getState());

    host.stop();

    assertNull(awaitService(SPIProvider.class.getName(), false));
    assertNull(awaitService(Runnable.class.getName(), false));
  }
}
