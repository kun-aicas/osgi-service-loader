/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.util.tracker.BundleTrackerCustomizer;

import java.util.List;

/**
 * Every non-fragment host bundle is inspected for consumer metadata when it
 * enters the tracked state set. Only hosts whose processor extender requirement
 * selects this mediator are registered with the mediator. Metadata-free
 * consumers are selected by the weaving hook and resolved at runtime.
 */
public class ServiceLoaderConsumerTracker implements
BundleTrackerCustomizer<Bundle>
{
  private final MediatorActivator activator_;

  /**
   * Creates a tracker that registers processed consumer Bundles with the
   * supplied mediator.
   *
   * @param baseActivator the active mediator
   */
  public ServiceLoaderConsumerTracker(MediatorActivator baseActivator)
  {
    this.activator_ = baseActivator;
  }

  /**
   * Inspects and registers a non-fragment consumer Bundle when it enters the
   * tracked state set.
   *
   * @return the tracked Bundle, or {@code null} for a fragment
   */
  @Override
  public Bundle addingBundle(Bundle bundle, BundleEvent event)
  {
    if (event != null)
    {
      MediatorActivator.printDebug("[CONSUMER_TRACKER] addingBundle[" +
                                   bundle.getBundleId() + "] " +
                                   ServiceLoaderProviderTracker.getState(event.getType()));
    }

    BundleRevision revision = bundle.adapt(BundleRevision.class);
    if (revision != null &&
        (revision.getTypes() & BundleRevision.TYPE_FRAGMENT) != 0)
      {
        return null;
      }
    // only the metadata consumer are processed, metadata-free consumer is checked
    // at runtime.
    registerConsumer(bundle);
    return bundle;
  }

  /**
   * Processes the ServiceLoader consumer requirements declared by the given
   * bundle and its attached fragments for the specified mediator bundle.
   *
   * <p>The host bundle revision and all attached fragment revisions are treated
   * as one consumer unit. If any of these revisions declares an
   * {@code osgi.extender} requirement for {@code osgi.serviceloader.processor},
   * this method determines whether the resolved wire selects this mediator.
   * When the host or an attached fragment declares an
   * {@code osgi.serviceloader} requirement, the consumer receives restricted
   * visibility based on its resolved service-loader wires. Otherwise, it has
   * unrestricted provider visibility.</p>
   *
   * <p>This method does not inspect consumer class bytecode, determine whether
   * a class invokes {@link java.util.ServiceLoader#load(Class)}, or resolve
   * {@code osgi.serviceloader} requirements by their declared filters. It does
   * inspect resolved extender wires to determine whether this mediator is the
   * selected processor and resolved service-loader wires to identify the
   * provider Bundles visible to a metadata-declaring consumer.</p>
   *
   * @param bundle the host bundle whose consumer requirements should be inspected.
   */
  private void registerConsumer(Bundle bundle)
  {
    List<BundleRevision> revisions = HeaderProcessor.getHostAndFragmentRevisions(bundle);

    // The Service Loader Mediator specification requires a Consumer to select
    // exactly one processor mediator. Reject a processor wire declared with
    // cardinality:=multiple to avoid processing the same Consumer through
    // multiple processor extenders.
    if (!HeaderProcessor.hasMediatorExtenderWire(revisions,
                                                 MediatorConstants.PROCESSOR_EXTENDER_NAME,
                                                 activator_.getMediatorBundle(),
                                                 true))
      {
        return;
      }
    ConsumerVisibility visibility =
            HeaderProcessor.hasServiceLoaderMetadata(bundle,
                    HeaderProcessor.MetadataKind.REQUIREMENT) ? ConsumerVisibility.fromResolvedWires(bundle) :
                                                                ConsumerVisibility.unrestricted();
    activator_.registerConsumerBundle(bundle, visibility);
  }

  /**
   * Leaves registration unchanged while the tracked Bundle remains in the
   * tracked state set. A new revision is registered after the Bundle leaves
   * and later re-enters that set.
   */
  @Override
  public void modifiedBundle(Bundle bundle, BundleEvent event,
                             Bundle ignored)
  {
    // Consumer registration is rebuilt when the Bundle leaves and re-enters
    // the tracked state set.
  }

  /**
   * Removes consumer registration and any associated provider lifecycle
   * dependencies when a tracked Bundle leaves the tracked state set.
   */
  @Override
  public void removedBundle(Bundle bundle, BundleEvent event,
                            Bundle ignored)
  {
    if(event!=null)
    {
      MediatorActivator.printDebug("[CONSUMER_TRACKER] removedBundle[" +
                                   bundle.getBundleId() + "] " +
                                   ServiceLoaderProviderTracker.getState(event.getType()));
    }
    activator_.unregisterConsumerBundle(bundle);
  }
}
