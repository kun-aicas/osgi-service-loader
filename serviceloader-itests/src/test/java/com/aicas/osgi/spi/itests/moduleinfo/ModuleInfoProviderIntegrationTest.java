/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.itests.moduleinfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.osgi.framework.Bundle;

import com.aicas.osgi.spi.itests.support.AbstractIntegrationTest;
import com.aicas.osgi.spi.example.spi.SPIProvider;

/** Verifies provider discovery from a {@code module-info.class} descriptor. */
public class ModuleInfoProviderIntegrationTest
    extends AbstractIntegrationTest
{
  @Test
  public void exposesTheModuleInfoProviderOnlyThroughServiceLoader()
      throws Exception
  {
    Bundle provider = install("module-info-provider",
                              "serviceloader-provider-moduleinfo-example");
    Bundle serviceLoaderConsumer = install("service-loader-consumer",
                                           "serviceloader-consumer-example");

    provider.start();
    serviceLoaderConsumer.start();

    assertNull(awaitService(SPIProvider.class.getName(), false));
    assertEquals(Bundle.ACTIVE, serviceLoaderConsumer.getState());
    assertOutputContains("[consumer] Get all the registered services:/",
                         MODULE_INFO_PROVIDER_MESSAGE);

    serviceLoaderConsumer.stop();
    provider.stop();
  }

  /**
   * Verifies that updating a module-info provider replaces its old provider
   * definition with the definition from the updated module descriptor.
   */
  @Test
  public void discoversUpdatedModuleInfoProvider()
      throws Exception
  {
    Bundle provider = install("module-info-provider",
                              "serviceloader-provider-moduleinfo-example");
    Bundle consumer = install("service-loader-consumer",
                              "serviceloader-consumer-example");

    provider.start();
    consumer.start();
    awaitOutputContains(MODULE_INFO_PROVIDER_MESSAGE);

    consumer.stop();
    update(provider, "serviceloader-provider-moduleinfo-update-example");

    consumer.start();
    awaitOutputContains(UPDATED_MODULE_INFO_PROVIDER_MESSAGE);
    assertOutputContainsExactlyOnce(MODULE_INFO_PROVIDER_MESSAGE,
                                    UPDATED_MODULE_INFO_PROVIDER_MESSAGE);

    consumer.stop();
    provider.stop();
    assertOutputContains("[consumer] stopped");
  }

  /**
   * Verifies that a bundle declaring both {@code uses} and {@code provides} in
   * {@code module-info.class} can consume the first provider it contributes.
   */
  @Test
  public void providerConsumerBundleLoadsItsFirstModuleInfoProvider()
      throws Exception
  {
    Bundle providerConsumer = install("provider-consumer",
        "serviceloader-provider-consumer-example");

    providerConsumer.start();

    assertEquals(Bundle.ACTIVE, providerConsumer.getState());
    assertNull(awaitService(SPIProvider.class.getName(), false));
    assertOutputContains("[provider-consumer] Result from the first SPI:",
                         PROVIDER_CONSUMER_MESSAGE);

    providerConsumer.stop();

    assertOutputContains("[provider-consumer] stopped");
  }

}
