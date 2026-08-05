/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.itests.metadata;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.osgi.framework.Bundle;

import com.aicas.osgi.spi.itests.support.AbstractIntegrationTest;

/** Verifies consumer metadata declared through OSGi bundle metadata. */
public class MetadataConsumerIntegrationTest extends AbstractIntegrationTest
{
  /**
   * Verifies that an attached consumer fragment enables ServiceLoader
   * processing and that removing it disables the processing again.
   *
   * <p>The host is first started without the fragment and cannot discover the
   * provider. It is then stopped, the fragment is installed, and the host
   * wiring is refreshed before restarting it. The fragment is then uninstalled,
   * the host is refreshed and restarted again, and it can no longer discover
   * the provider. Restarts are required because already loaded classes cannot
   * be woven or un-woven retroactively.</p>
   */
  @Test
  public void consumerFragmentControlsServiceLoaderProcessing()
      throws Exception
  {
    Bundle host = installTestBundle("consumer-fragment-host",
        "serviceloader-consumer-fragment-host");
    Bundle provider = install("provider", "serviceloader-provider-example");

    provider.start();
    host.start();

    assertEquals(Bundle.ACTIVE, host.getState());
    awaitOutputContains("[fragment consumer host] First SPI provider:",
                        "[fragment consumer host] No provider found.");

    host.stop();

    int attachedOutputOffset = outputLength();
    Bundle fragment = installTestBundle("consumer-fragment",
        "serviceloader-consumer-fragment");
    refreshBundles(host);

    host.start();
    assertEquals(Bundle.ACTIVE, host.getState());
    assertEquals(Bundle.RESOLVED, fragment.getState());
    awaitOutputContainsAfter(attachedOutputOffset,
                             REGULAR_PROVIDER_MESSAGE);

    host.stop();

    int removalOutputOffset = outputLength();
    fragment.uninstall();
    refreshBundles(host);

    host.start();
    assertEquals(Bundle.ACTIVE, host.getState());
    awaitOutputContainsAfter(removalOutputOffset,
        "[fragment consumer host] No provider found.");

    host.stop();
    provider.stop();

    assertOutputContains("[fragment consumer host] stopped");
  }
}
