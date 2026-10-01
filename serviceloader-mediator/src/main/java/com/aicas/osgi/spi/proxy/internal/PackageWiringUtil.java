/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

/** Utilities for resolving and comparing effective OSGi package sources. */
final class PackageWiringUtil
{
  static final String JAVA_PACKAGE_PREFIX = "java.";

  private PackageWiringUtil()
  {
  }

  /**
   * Resolves the package exporter effectively visible to a bundle.
   *
   * <p>A direct {@code Import-Package} wire takes precedence. Otherwise the
   * method follows resolved {@code Require-Bundle} wires recursively, before
   * falling back to the bundle's own package export. A revision is visited at
   * most once in a resolution branch, so Require-Bundle cycles terminate.</p>
   *
   * @param bundle the bundle whose package space is inspected.
   * @param packageName the package to resolve.
   * @return the selected exporter capability, or {@code null} when no OSGi
   *         package source applies.
   */
  static BundleCapability effectivePackageCapability(Bundle bundle,
                                                     String packageName)
  {
    Objects.requireNonNull(bundle, "bundle");
    Objects.requireNonNull(packageName, "packageName");
    if (packageName.isEmpty() ||
        packageName.startsWith(JAVA_PACKAGE_PREFIX))
      {
        return null;
      }
    return effectivePackageCapability(bundle, packageName, new HashSet<>());
  }


  /**
   * Resolves a package exporter while traversing a Require-Bundle branch.
   *
   * <p>This recursive helper follows package imports and required bundles for
   * the supplied bundle, then considers its own exports. The set tracks the
   * revisions on the current Require-Bundle path. Each invocation adds its
   * revision before traversing providers and removes it when the branch
   * finishes. An already present revision terminates that branch, preventing
   * cycles while allowing sibling branches to inspect the same revision.</p>
   *
   * @param bundle the bundle whose package space is inspected.
   * @param packageName the package to resolve.
   * @param visitedRevisions revisions on the current Require-Bundle path.
   * @return the selected exporter capability, or {@code null} when no OSGi
   *         package source applies or a cycle is encountered.
   */
  private static BundleCapability effectivePackageCapability(Bundle bundle,
                                                             String packageName,
                                                             Set<BundleRevision> visitedRevisions)
  {
    BundleWiring wiring = bundle.adapt(BundleWiring.class);
    if (wiring == null)
      {
        return null;
      }

    BundleRevision revision = wiring.getRevision();
    if (revision != null && !visitedRevisions.add(revision))
      {
        // The revision is already on this Require-Bundle path.
        return null;
      }

    try
      {
        // import-package
        BundleCapability importedPackage =
            importCapability(wiring.getRequiredWires(BundleRevision.PACKAGE_NAMESPACE),
                             packageName);
        if (importedPackage != null)
          {
            return importedPackage;
          }

        // require-bundle
        List<BundleWire> requiredBundleWires =
          wiring.getRequiredWires(BundleRevision.BUNDLE_NAMESPACE);
        if (requiredBundleWires != null)
          {
            for (BundleWire wire : requiredBundleWires)
              {
                BundleWiring rWiring = wire == null ? null :
                                                      wire.getProviderWiring();
                Bundle providerBundle = rWiring == null ? null :
                                                          rWiring.getBundle();
                if (providerBundle == null)
                  {
                    continue;
                  }
                BundleCapability capability = effectivePackageCapability(providerBundle,
                                                                         packageName,
                                                                         visitedRevisions);
                if (capability != null)
                  {
                    return capability;
                  }
              }
          }

        return exportCapability(
            wiring.getCapabilities(BundleRevision.PACKAGE_NAMESPACE), packageName);
      }
    finally
      {
        // removes the revision afterward, so it tracks only the current traversal path.
        // That lets another independent Require-Bundle branch inspect the same revision
        // normally.
        if (revision != null)
          {
            visitedRevisions.remove(revision);
          }
      }
  }

  /**
   * Finds the requested package among this bundle's {@code Export-Package}
   * capabilities.
   *
   * <p>This fallback runs after direct package imports and {@code Require-Bundle}
   * providers have been considered. Missing capability lists and {@code null}
   * capabilities are ignored.</p>
   *
   * @param capabilities the package-export capabilities to inspect.
   * @param packageName the package to match.
   * @return the matching export capability, or {@code null} when this bundle
   *         does not export the requested package.
   */
  private static BundleCapability
      exportCapability(List<BundleCapability> capabilities,
                       String packageName)
  {
    if (capabilities != null)
      {
        for (BundleCapability capability : capabilities)
          {
            if (capability != null &&
                packageName.equals(capability.getAttributes().
                                   get(BundleRevision.PACKAGE_NAMESPACE)))
              {
                return capability;
              }
          }
      }
    return null;
  }

  /**
   * Finds the first required package wire for the requested package.
   *
   * <p>The wires represent resolved {@code Import-Package} metadata; they can
   * also represent dynamically established {@code DynamicImport-Package}
   * wires. Missing wire lists and {@code null} wires are ignored.</p>
   *
   * @param wires the required package wires to inspect.
   * @param packageName the package to match.
   * @return the matching package capability, or {@code null} when no wire
   *         provides the requested package.
   */
  private static BundleCapability importCapability(List<BundleWire> wires,
                                                    String packageName)
  {
    if (wires != null)
      {
        for (BundleWire wire : wires)
          {
            BundleCapability capability = wire == null ? null :
                                                         wire.getCapability();
            if (capability != null &&
                packageName.equals(capability.getAttributes().
                                   get(BundleRevision.PACKAGE_NAMESPACE)))
              {
                return capability;
              }
          }
      }
    return null;
  }

  /**
   * Returns whether two resolved package capabilities identify the same class
   * space.
   *
   * <p>A {@code null} capability never matches, including another
   * {@code null} capability. Callers must not use this method for
   * {@code java.*} packages, which are supplied by the JVM bootstrap loader.</p>
   */
  static boolean sameCapability(BundleCapability a, BundleCapability b)
  {
    if (a == null || b == null)
      {
        return false;
      }
    if (a == b || a.equals(b))
      {
        return true;
      }
    return Objects.equals(a.getRevision(), b.getRevision()) &&
           Objects.equals(a.getAttributes(), b.getAttributes());
  }

  static String packageOf(String className)
  {
    int dollar = className.indexOf('$');
    String outer = dollar > 0 ? className.substring(0, dollar) : className;
    int dot = outer.lastIndexOf('.');
    return dot > 0 ? outer.substring(0, dot) : "";
  }
}
