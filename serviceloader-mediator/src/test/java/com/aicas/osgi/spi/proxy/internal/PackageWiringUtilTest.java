/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

/** Tests effective package-source resolution through resolved OSGi wires. */
public class PackageWiringUtilTest
{
  private static final String PACKAGE_NAME = "com.example.spi";

  @Test
  public void directImportTakesPrecedenceOverRequiredBundle()
  {
    BundleCapability imported = packageCapability();
    BundleCapability requiredExport = packageCapability();
    Bundle required = bundleWithSources(mock(BundleRevision.class), null,
                                        requiredExport, List.of());
    Bundle consumer = bundleWithSources(mock(BundleRevision.class), imported,
                                        null, List.of(wireTo(required)));

    assertSame(imported, PackageWiringUtil.effectivePackageCapability(consumer,
                                                                      PACKAGE_NAME));
  }

  @Test
  public void requiredBundleImportSuppliesTheEffectiveSource()
  {
    BundleCapability importedByRequiredBundle = packageCapability();
    Bundle required = bundleWithSources(mock(BundleRevision.class),
                                        importedByRequiredBundle, null,
                                        List.of());
    Bundle consumer = bundleWithSources(mock(BundleRevision.class), null,
                                        null, List.of(wireTo(required)));

    assertSame(importedByRequiredBundle,
               PackageWiringUtil.effectivePackageCapability(consumer,
                                                            PACKAGE_NAME));
  }

  @Test
  public void requiredBundleChainResolvesTheFinalExporter()
  {
    BundleCapability finalExport = packageCapability();
    Bundle last = bundleWithSources(mock(BundleRevision.class), null,
                                    finalExport, List.of());
    Bundle middle = bundleWithSources(mock(BundleRevision.class), null,
                                      null, List.of(wireTo(last)));
    Bundle consumer = bundleWithSources(mock(BundleRevision.class), null,
                                        null, List.of(wireTo(middle)));

    assertSame(finalExport,
               PackageWiringUtil.effectivePackageCapability(consumer,
                                                            PACKAGE_NAME));
  }

  @Test
  public void requireBundleCycleWithoutPackageExportTerminates()
  {
    Bundle first = mock(Bundle.class);
    Bundle second = mock(Bundle.class);
    BundleRevision firstRevision = mock(BundleRevision.class);
    BundleRevision secondRevision = mock(BundleRevision.class);
    configureSources(first, firstRevision, null, null, List.of(wireTo(second)));
    configureSources(second, secondRevision, null, null,
                     List.of(wireTo(first)));

    assertNull(PackageWiringUtil.effectivePackageCapability(first,
                                                            PACKAGE_NAME));
  }

  @Test
  public void ownExportIsUsedWhenNoImportOrRequiredBundleSuppliesPackage()
  {
    BundleCapability ownExport = packageCapability();
    Bundle unavailableRequiredBundle = bundleWithSources(mock(BundleRevision.class),
                                                         null, null, List.of());
    Bundle consumer = bundleWithSources(mock(BundleRevision.class),
                                        null,
                                        ownExport,
                                        List.of(wireTo(unavailableRequiredBundle)));

    assertSame(ownExport,
               PackageWiringUtil.effectivePackageCapability(consumer,
                                                            PACKAGE_NAME));
  }

  private static Bundle bundleWithSources(BundleRevision revision,
                                          BundleCapability importedPackage,
                                          BundleCapability exportedPackage,
                                          List<BundleWire> requiredBundleWires)
  {
    Bundle bundle = mock(Bundle.class);
    configureSources(bundle, revision, importedPackage, exportedPackage,
                     requiredBundleWires);
    return bundle;
  }

  private static void configureSources(Bundle bundle,
                                       BundleRevision revision,
                                       BundleCapability importedPackage,
                                       BundleCapability exportedPackage,
                                       List<BundleWire> requiredBundleWires)
  {
    BundleWiring wiring = mock(BundleWiring.class);
    when(bundle.adapt(BundleWiring.class)).thenReturn(wiring);
    when(wiring.getRevision()).thenReturn(revision);
    when(wiring.getRequiredWires(BundleRevision.BUNDLE_NAMESPACE)).
                thenReturn(requiredBundleWires);
    List<BundleWire> packageWires =
      importedPackage == null ? List.of()
                              : List.of(packageWire(importedPackage));
    when(wiring.getRequiredWires(BundleRevision.PACKAGE_NAMESPACE)).
                thenReturn(packageWires);
    when(wiring.getCapabilities(BundleRevision.PACKAGE_NAMESPACE)).
                thenReturn(exportedPackage == null ? List.of() :
                                                     List.of(exportedPackage));
  }

  private static BundleWire wireTo(Bundle providerBundle)
  {
    BundleWire wire = mock(BundleWire.class);
    BundleWiring providerWiring = mock(BundleWiring.class);
    when(wire.getProviderWiring()).thenReturn(providerWiring);
    when(providerWiring.getBundle()).thenReturn(providerBundle);
    return wire;
  }

  private static BundleWire packageWire(BundleCapability capability)
  {
    BundleWire wire = mock(BundleWire.class);
    when(wire.getCapability()).thenReturn(capability);
    return wire;
  }

  private static BundleCapability packageCapability()
  {
    BundleCapability capability = mock(BundleCapability.class);
    when(capability.getAttributes())
        .thenReturn(Map.of(BundleRevision.PACKAGE_NAMESPACE, PACKAGE_NAME));
    return capability;
  }
}
