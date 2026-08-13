/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.itests.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;

import com.aicas.osgi.spi.itests.support.AbstractIntegrationTest;
import com.aicas.osgi.spi.example.spi.SPIProvider;

public class CommonProviderIntegrationTest extends AbstractIntegrationTest
{
  /**
   * Verifies that the mediator starts inactive provider bundles on demand.
   *
   * <p>The test combines the regular metadata provider, the module-info
   * provider, and the fragment provider. None of the provider bundles is
   * started before the consumer. Starting the consumer must therefore
   * cause the mediator to start the providers before returning their
   * implementations.</p>
   */
  @Test
  public void startsProviders()
      throws Exception
  {
    Bundle provider = install("provider", "serviceloader-provider-example");
    Bundle moduleInfoProvider = install("module-info-provider",
                                        "serviceloader-provider-moduleinfo-example");
    Bundle fragment = install("provider-fragment",
                              "serviceloader-provider-fragment-example");
    Bundle fragmentHost = install("fragment-provider-host",
                                  "serviceloader-provider-fragment-host-example");
    Bundle consumer = install("service-loader-consumer",
                              "serviceloader-consumer-example");

    provider.stop();
    moduleInfoProvider.stop();
    fragmentHost.stop();

    consumer.start();

    awaitOutputContains(REGULAR_PROVIDER_MESSAGE,
                        MODULE_INFO_PROVIDER_MESSAGE,
                        FRAGMENT_PROVIDER_MESSAGE);
    assertEquals(Bundle.ACTIVE, provider.getState());
    assertEquals(Bundle.ACTIVE, moduleInfoProvider.getState());
    assertEquals(Bundle.ACTIVE, fragmentHost.getState());
    assertEquals(Bundle.RESOLVED, fragment.getState());
    assertNotNull(awaitService(SPIProvider.class.getName(), true));

    consumer.stop();
    provider.stop();
    moduleInfoProvider.stop();
    fragmentHost.stop();

    assertNull(awaitService(SPIProvider.class.getName(), false));
    assertOutputContains("[consumer] stopped");
  }

  /**
   * Verifies that a consumer discovers every installed provider exactly once.
   *
   * <p>This uses the regular Service Loader consumer, which iterates over all
   * providers. The provider-consumer bundle is intentionally not installed
   * because it only loads the first provider.</p>
   */
  @Test
  public void discoversAllProvidersOnce()
      throws Exception
  {
    Bundle provider = install("provider", "serviceloader-provider-example");
    Bundle moduleInfoProvider = install("module-info-provider",
                                        "serviceloader-provider-moduleinfo-example");
    Bundle fragment = install("provider-fragment",
                              "serviceloader-provider-fragment-example");
    Bundle fragmentHost = install("fragment-provider-host",
                                  "serviceloader-provider-fragment-host-example");
    Bundle consumer = install("service-loader-consumer",
                              "serviceloader-consumer-example");

    provider.start();
    moduleInfoProvider.start();
    fragmentHost.start();
    consumer.start();

    awaitOutputContains(REGULAR_PROVIDER_MESSAGE,
                        MODULE_INFO_PROVIDER_MESSAGE,
                        FRAGMENT_PROVIDER_MESSAGE);
    assertOutputContainsExactlyOnce(REGULAR_PROVIDER_MESSAGE,
                                    MODULE_INFO_PROVIDER_MESSAGE,
                                    FRAGMENT_PROVIDER_MESSAGE);
    consumer.stop();
    fragmentHost.stop();
    moduleInfoProvider.stop();
    provider.stop();

    assertNull(awaitService(SPIProvider.class.getName(), false));
    assertOutputContains("[consumer] stopped");
  }

  /**
   * Verifies the OSGi registration rules for the different provider forms.
   *
   * <p>The regular provider is registered as an OSGi service because it has
   * registrar metadata. The module-info and fragment providers are only
   * available through ServiceLoader. Their three provider messages must still
   * be discovered by the consumer.</p>
   */
  @Test
  public void appliesProviderRegistrationRules()
      throws Exception
  {
    Bundle provider = install("provider", "serviceloader-provider-example");
    Bundle moduleInfoProvider = install("module-info-provider",
                                        "serviceloader-provider-moduleinfo-example");
    Bundle fragment = install("provider-fragment",
                              "serviceloader-provider-fragment-example");
    Bundle fragmentHost = install("fragment-provider-host",
                                  "serviceloader-provider-fragment-host-example");
    Bundle consumer = install("service-loader-consumer",
                              "serviceloader-consumer-example");

    provider.start();
    moduleInfoProvider.start();
    fragmentHost.start();
    consumer.start();

    awaitOutputContains(REGULAR_PROVIDER_MESSAGE,
                        MODULE_INFO_PROVIDER_MESSAGE,
                        FRAGMENT_PROVIDER_MESSAGE);

    ServiceReference<?> reference =
        awaitService(SPIProvider.class.getName(), true);
    assertNotNull(reference);
    assertEquals(provider, reference.getBundle());
    assertEquals(1, awaitServiceCount(SPIProvider.class.getName(), 1));

    consumer.stop();
    fragmentHost.stop();
    moduleInfoProvider.stop();
    provider.stop();

    assertEquals(0, awaitServiceCount(SPIProvider.class.getName(), 0));
    assertOutputContains("[consumer] stopped");
  }

  /**
   * Verifies that updating a provider bundle replaces its mediator definition
   * and OSGi service registration with the definition from the new revision.
   */
  @Test
  public void discoversProviderUpdate()
      throws Exception
  {
    Bundle provider = install("provider", "serviceloader-provider-example");
    Bundle consumer = install("service-loader-consumer",
                              "serviceloader-consumer-example");
    Bundle osgiConsumer = install("osgi-consumer",
                                  "serviceloader-osgi-client-example");

    provider.start();
    consumer.start();
    osgiConsumer.start();
    awaitOutputContains(REGULAR_PROVIDER_MESSAGE);
    awaitOutputContains("[osgi client] - " + REGULAR_PROVIDER_MESSAGE);

    ServiceReference<?> oldReference =
        awaitService(SPIProvider.class.getName(), true);
    assertNotNull(oldReference);

    int updateOutputOffset = outputLength();
    update(provider, "serviceloader-provider-update-example");

    ServiceReference<?> newReference =
        awaitService(SPIProvider.class.getName(), true);
    assertNotNull(newReference);
    assertNotSame(oldReference, newReference);
    assertEquals(provider, newReference.getBundle());
    assertEquals(1, awaitServiceCount(SPIProvider.class.getName(), 1));

    consumer.stop();
    osgiConsumer.stop();
    consumer.start();
    osgiConsumer.start();
    awaitOutputContainsAfter(updateOutputOffset,
                              UPDATED_PROVIDER_MESSAGE,
                              "[osgi client] - " + UPDATED_PROVIDER_MESSAGE);
    consumer.stop();
    osgiConsumer.stop();
    provider.stop();
    assertEquals(0, awaitServiceCount(SPIProvider.class.getName(), 0));
    assertOutputContains("[consumer] stopped", "[osgi client] Stopped");
  }

  /**
   * Verifies that a provider construction failure is isolated by the
   * mediator and does not prevent valid providers from being consumed.
   */
  @Test
  public void ignoresFailingProvider()
      throws Exception
  {
    Bundle provider = install("provider", "serviceloader-provider-example");
    Bundle failingProvider = installTestBundle(
        "failing-provider", "serviceloader-provider-failure-example");
    Bundle consumer = install("service-loader-consumer",
                              "serviceloader-consumer-example");

    provider.start();
    failingProvider.start();
    consumer.start();

    awaitOutputContains("[failing provider] construction attempted",
                        REGULAR_PROVIDER_MESSAGE);
    assertOutputContainsExactlyOnce(REGULAR_PROVIDER_MESSAGE);
    assertEquals(Bundle.ACTIVE, consumer.getState());
    assertOutputDoesNotContain("[consumer] Failed");

    consumer.stop();
    failingProvider.stop();
    provider.stop();

    assertEquals(0, awaitServiceCount(SPIProvider.class.getName(), 0));
    assertOutputContains("[consumer] stopped");
  }

}
