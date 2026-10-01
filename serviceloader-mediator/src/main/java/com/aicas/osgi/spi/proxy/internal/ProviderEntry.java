/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import java.util.Objects;
import java.util.Set;

import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleCapability;

/**
 * All Service Provider implementations of one Service Type discovered in one
 * Provider bundle.
 *
 * <p>Instances describe the discovered implementations independently of
 * optional {@code osgi.serviceloader} capability metadata. That metadata is
 * represented separately by {@link ProviderCapability},
 * because it controls exposure and OSGi service registration rather than
 * identifying Provider implementations.</p>
 *
 * @param serviceType the fully qualified name of the Service Type implemented
 *        by every class in {@code implementationClasses}.
 * @param providerBundle the bundle that contains the Provider implementation
 *        classes.
 * @param packageCapability the resolved {@code osgi.wiring.package}
 *        capability for the Service Type's package. It is {@code null} when no
 *        package capability applies yet, for example for a Provider whose
 *        Service Type uses an untriggered dynamic import, a package private to
 *        the Provider bundle, or boot delegation.
 * @param implementationClasses the fully qualified names of the Provider
 *        implementation classes. The list is copied when this entry is
 *        created.
 */
record ProviderEntry(String serviceType,
                     Bundle providerBundle,
                     BundleCapability packageCapability,
                     Set<String> implementationClasses)
{
  ProviderEntry
  {
    serviceType = Objects.requireNonNull(serviceType, "serviceType");
    providerBundle = Objects.requireNonNull(providerBundle, "providerBundle");
    implementationClasses = Set.copyOf(Objects.requireNonNull(implementationClasses,
                                                              "implementationClasses"));
  }

  /**
   * Returns this entry with the resolved Service Type package capability.
   *
   * <p>The replacement preserves the immutable entry model while allowing the
   * mediator to retain a package capability established by a Provider's
   * dynamic import.</p>
   *
   * @param resolvedPackageCapability a non-null capability from the Provider's
   *        current wiring.
   * @return this entry when it already has that capability; otherwise an entry
   *         with the supplied capability.
   */
  ProviderEntry withResolvedPackageCapability(BundleCapability resolvedPackageCapability)
  {
    Objects.requireNonNull(resolvedPackageCapability,
                           "resolvedPackageCapability");
    return packageCapability == resolvedPackageCapability ? this
                                                          : new ProviderEntry(serviceType,
                                                                              providerBundle,
                                                                              resolvedPackageCapability,
                                                                              implementationClasses);
  }

}
