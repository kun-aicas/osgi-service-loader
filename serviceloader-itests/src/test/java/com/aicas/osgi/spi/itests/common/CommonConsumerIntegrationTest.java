/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.itests.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.osgi.framework.Bundle;

import com.aicas.osgi.spi.itests.support.AbstractIntegrationTest;
import com.aicas.osgi.spi.example.spi.SPIProvider;

/** Verifies consumer behavior independent of a particular metadata form. */
public class CommonConsumerIntegrationTest extends AbstractIntegrationTest
{
  /** Verifies that a consumer can start when no provider bundle is available. */
  @Test
  public void startsConsumerWithoutProviders()
      throws Exception
  {
    Bundle consumer = install("service-loader-consumer",
                              "serviceloader-consumer-example");
    consumer.start();

    assertEquals(Bundle.ACTIVE, consumer.getState());
    assertNull(awaitService(SPIProvider.class.getName(), false));
    awaitOutputContains("[consumer] No provider found.");

    consumer.stop();
    assertOutputContains("[consumer] stopped");
  }

  /**
   * Verifies that a consumer without mediator metadata is not woven.
   *
   * <p>The provider is available and registered as an OSGi service, but the
   * consumer declares neither an OSGi ServiceLoader requirement nor a
   * module-info {@code uses} directive. Its valid ServiceLoader call must
   * therefore remain unprocessed.</p>
   */
  @Test
  public void doesNotProcessConsumerWithoutMetadata()
      throws Exception
  {
    Bundle provider = install("provider", "serviceloader-provider-example");
    Bundle consumer = installTestBundle("unprocessed-consumer",
                                        "serviceloader-unprocessed-consumer");

    provider.start();
    assertNotNull(awaitService(SPIProvider.class.getName(), true));

    consumer.start();

    assertEquals(Bundle.ACTIVE, consumer.getState());
    awaitOutputContains("[unprocessed consumer] No provider found.");
    assertOutputDoesNotContain(REGULAR_PROVIDER_MESSAGE);

    consumer.stop();
    provider.stop();
    assertOutputContains("[unprocessed consumer] stopped");
  }

  /** Verifies that ServiceLoader calls in an embedded JAR are woven. */
  @Test
  public void processesConsumerCodeInEmbeddedJar()
      throws Exception
  {
    Bundle provider = install("provider", "serviceloader-provider-example");
    Bundle consumer = installTestBundle("embedded-consumer",
                                        "serviceloader-embedded-consumer-bundle");

    provider.start();
    consumer.start();

    assertEquals(Bundle.ACTIVE, consumer.getState());
    awaitOutputContains("[embedded consumer] " + REGULAR_PROVIDER_MESSAGE);

    consumer.stop();
    provider.stop();
    assertOutputContains("[embedded consumer] stopped");
  }

  /**
   * Verifies that an updated consumer is woven using its updated metadata.
   *
   * <p>The original consumer requires {@link SPIProvider}. Its updated
   * revision instead requires {@link Runnable}, and invokes ServiceLoader for
   * that type. The two-service provider host supplies Runnable through its
   * attached fragment.</p>
   */
  @Test
  public void processesUpdatedConsumerMetadata()
      throws Exception
  {
    Bundle provider = install("provider", "serviceloader-provider-example");
    Bundle consumer = install("service-loader-consumer",
                              "serviceloader-consumer-example");

    provider.start();
    consumer.start();
    awaitOutputContains(REGULAR_PROVIDER_MESSAGE);

    consumer.stop();
    Bundle fragment = installTestBundle("two-service-provider-fragment",
        "serviceloader-two-service-provider-fragment");
    Bundle host = installTestBundle("two-service-provider-host",
        "serviceloader-two-service-provider-host");
    host.start();

    int updateOutputOffset = outputLength();
    update(consumer, "serviceloader-consumer-update-example");
    consumer.start();

    assertEquals(Bundle.ACTIVE, consumer.getState());
    awaitOutputContainsAfter(updateOutputOffset,
        "[updated consumer] Runnable provider found.");
    assertOutputContainsExactlyOnce(REGULAR_PROVIDER_MESSAGE,
                                    "[updated consumer] Runnable provider found.");

    consumer.stop();
    host.stop();
    provider.stop();
    assertEquals(Bundle.RESOLVED, fragment.getState());
    assertOutputContains("[updated consumer] stopped");
  }
}
