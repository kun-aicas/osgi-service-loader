/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.itests.advanced;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

import com.aicas.osgi.spi.itests.support.AbstractTest;

/** Verifies metadata-free discovery through a consumer dynamic package import. */
public class DynamicImportTest extends AbstractTest
{
  private static final String SPI_PACKAGE = "com.aicas.osgi.spi.example.spi";

  @Test
  public void dynamicImportConsumerDiscoversCompatibleProvider()
    throws Exception
  {
    Bundle provider = installTestBundle(BUNDLE_PROVIDER_V2);
    Bundle consumer = installTestBundle(BUNDLE_DYNAMIC_IMPORT_CONSUMER_V2);

    String staticImports = consumer.getHeaders().get(Constants.IMPORT_PACKAGE);
    String dynamicImports = consumer.getHeaders().get(Constants.DYNAMICIMPORT_PACKAGE);
    assertFalse("The SPI package must not be statically imported",
                staticImports != null && staticImports.contains(SPI_PACKAGE));
    assertTrue("The SPI package must be dynamically imported",
               dynamicImports != null && dynamicImports.contains(SPI_PACKAGE));

    provider.start();
    int outputOffset = outputLength();
    consumer.start();

    awaitOutputContainsAfter(outputOffset, V2_PROVIDER_MESSAGE);
    assertDynamicSpiPackageWire(consumer);

    consumer.stop();
    provider.stop();
  }

  @Test
  public void dynamicImportProviderIsDiscoveredByCompatibleConsumer()
    throws Exception
  {
    Bundle provider = installTestBundle(BUNDLE_DYNAMIC_IMPORT_PROVIDER_V2);
    Bundle consumer = installTestBundle(BUNDLE_CONSUMER_V2);

    assertDynamicallyImportsSpiPackage(provider);

    provider.start();
    int outputOffset = outputLength();
    consumer.start();

    awaitOutputContainsAfter(outputOffset, DYNAMIC_IMPORT_PROVIDER_V2_MESSAGE);
    assertDynamicSpiPackageWire(provider);

    consumer.stop();
    provider.stop();
  }

  @Test
  public void dynamicImportConsumerDiscoversDynamicImportProvider()
    throws Exception
  {
    Bundle provider = installTestBundle(BUNDLE_DYNAMIC_IMPORT_PROVIDER_V2);
    Bundle consumer = installTestBundle(BUNDLE_DYNAMIC_IMPORT_CONSUMER_V2);

    assertDynamicallyImportsSpiPackage(provider);
    assertDynamicallyImportsSpiPackage(consumer);

    provider.start();
    int outputOffset = outputLength();
    consumer.start();

    awaitOutputContainsAfter(outputOffset, DYNAMIC_IMPORT_PROVIDER_V2_MESSAGE);
    assertDynamicSpiPackageWire(consumer);
    assertDynamicSpiPackageWire(provider);

    consumer.stop();
    provider.stop();
  }

  private static void assertDynamicallyImportsSpiPackage(Bundle bundle)
  {
    String staticImports = bundle.getHeaders().get(Constants.IMPORT_PACKAGE);
    String dynamicImports = bundle.getHeaders().get(Constants.DYNAMICIMPORT_PACKAGE);
    assertFalse("The SPI package must not be statically imported",
                staticImports != null && staticImports.contains(SPI_PACKAGE));
    assertTrue("The SPI package must be dynamically imported",
               dynamicImports != null && dynamicImports.contains(SPI_PACKAGE));
  }

  private static void assertDynamicSpiPackageWire(Bundle consumer)
  {
    BundleWiring wiring = consumer.adapt(BundleWiring.class);
    assertNotNull("Consumer wiring is unavailable", wiring);
    assertTrue("The Service Type dynamic package wire was not established",
        wiring.getRequiredWires(BundleRevision.PACKAGE_NAMESPACE).stream()
              .anyMatch(DynamicImportTest::isSpiPackageWire));
  }

  private static boolean isSpiPackageWire(BundleWire wire)
  {
    return wire.getCapability() != null &&
           SPI_PACKAGE.equals(wire.getCapability().getAttributes().
                              get(BundleRevision.PACKAGE_NAMESPACE));
  }
}
