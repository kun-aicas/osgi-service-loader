/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleDescriptor;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.util.tracker.BundleTrackerCustomizer;

/**
 * Every non-fragment host bundle is inspected for consumer metadata. Its
 * metadata is refreshed when the host revision or its attached-fragment
 * revision set changes, and removed when the host leaves the tracked state
 * set. Only hosts that declare Service Loader consumer metadata through
 * {@code osgi.serviceloader} requirements or {@code uses} declarations in
 * {@code module-info.class} are registered for weaving.
 */
public class ServiceLoaderConsumerTracker implements
BundleTrackerCustomizer<ServiceLoaderConsumerTracker.ConsumerBundleInfo>
{
  /**
   * Tracks the host revision and attached fragment revisions used to discover
   * one consumer bundle's metadata.
   */
  static class ConsumerBundleInfo
  {
    private List<BundleRevision> revisions_;

    ConsumerBundleInfo(List<BundleRevision> revisions)
    {
      revisions_ = List.copyOf(revisions);
    }

    boolean hasSameRevisions(List<BundleRevision> revisions)
    {
      return revisions_.equals(revisions);
    }

    void updateRevisions(List<BundleRevision> revisions)
    {
      revisions_ = List.copyOf(revisions);
    }
  }

  private final MediatorActivator activator_;

  public ServiceLoaderConsumerTracker(MediatorActivator baseActivator)
  {
    this.activator_ = baseActivator;
  }

  @Override
  public ConsumerBundleInfo addingBundle(Bundle bundle, BundleEvent event)
  {
    if (event != null)
    {
      MediatorActivator.printDebug("[CONSUMER_TRACKER] addingBundle[" + bundle.getBundleId() + "] " +
              ServiceLoaderProviderTracker.getState(event.getType()));
    }

    BundleRevision revision = bundle.adapt(BundleRevision.class);
    if (revision != null &&
        (revision.getTypes() & BundleRevision.TYPE_FRAGMENT) != 0)
      {
        return null;
      }

    ConsumerBundleInfo info = new ConsumerBundleInfo(
        HeaderProcessor.getHostAndFragmentRevisions(bundle));
    registerConsumerMetadata(bundle);
    return info;
  }

  /**
   * Reads and registers the current consumer metadata for a host bundle.
   *
   * <p>Invalid metadata is logged and ignored so that one malformed consumer
   * does not prevent the tracker from processing other bundles.</p>
   */
  private boolean registerConsumerMetadata(Bundle bundle)
  {
    try
      {
        HeaderProcessor.ConsumerRequirementResult result =
            HeaderProcessor.processConsumerRequirements(
            bundle, activator_.getMediatorBundle());
        if (result.isSelectedByThisMediator())
          {
            activator_.registerConsumerBundle(bundle, result.getServiceTypes());
          }
        else if (!result.hasProcessorRequirement())
          {
            Set<String> serviceTypes = readModuleInfoUses(bundle);
            if (!serviceTypes.isEmpty())
              {
                activator_.registerConsumerBundle(bundle, serviceTypes);
              }
          }
        return true;
      }
    catch (IOException e)
      {
        MediatorActivator.logger_.warn(
            "Could not read Service Loader consumer metadata from bundle " +
            bundle.getSymbolicName() + " (" + bundle.getBundleId() + ")",
            e);
        return false;
      }
  }

  /**
   * Determines whether the specified bundle declares at least one ServiceLoader
   * consumer in its {@code module-info.class}.
   *
   * <p>A bundle is considered a consumer when its module descriptor contains at
   * least one {@code uses <service-type>} declaration.</p>
   *
   * @param bundle the bundle to inspect
   * @return the service types declared by the module descriptor, or an empty
   *         set if no module descriptor exists or no {@code uses} declaration
   *         is present
   * @throws IOException
   */
  private Set<String> readModuleInfoUses(Bundle bundle)
    throws IOException
  {
    URL moduleInfoURL =
      bundle.getEntry(MediatorConstants.MODULE_INFO);

    if (moduleInfoURL == null)
      {
        return Collections.emptySet();
      }

    try (InputStream input = moduleInfoURL.openStream())
      {
        return ModuleDescriptor.read(input).uses();
      }
    catch (IOException | InvalidModuleDescriptorException e)
      {
        throw new IOException("Could not read module descriptor from bundle " +
                              bundle.getBundleId() + ": " + moduleInfoURL,
                              e);
      }
  }

  /**
   * Refreshes consumer metadata when a bundle update replaces the host revision
   * or when fragment attachment changes the host-and-fragment revision set.
   *
   * <p>A bundle update creates a new class space. Classes subsequently loaded
   * from that revision are presented to the weaving hook and are therefore
   * woven according to the refreshed consumer metadata. Already loaded classes
   * from the previous revision are not woven again.</p>
   */
  @Override
  public void modifiedBundle(Bundle bundle, BundleEvent event,
                             ConsumerBundleInfo info)
  {
    if (info == null)
      {
        return;
      }

    List<BundleRevision> currentRevisions =
        HeaderProcessor.getHostAndFragmentRevisions(bundle);
    if (!info.hasSameRevisions(currentRevisions) ||
        (event != null && event.getType() == BundleEvent.RESOLVED))
      {
        MediatorActivator.printDebug("[CONSUMER_TRACKER] Refresh[" +
                                     bundle.getBundleId() + "]");
        activator_.unregisterConsumerBundle(bundle);
        if (registerConsumerMetadata(bundle))
          {
            info.updateRevisions(currentRevisions);
          }
      }
  }

  @Override
  public void removedBundle(Bundle bundle, BundleEvent event,
                            ConsumerBundleInfo info)
  {
    if(event!=null)
    {
      MediatorActivator.printDebug("[CONSUMER_TRACKER] removedBundle[" + bundle.getBundleId() + "] "
              + ServiceLoaderProviderTracker.getState(event.getType()));
    }
    activator_.unregisterConsumerBundle(bundle);
  }

}
