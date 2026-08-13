/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.service.log.Logger;
import org.osgi.service.log.LoggerFactory;
import org.osgi.util.tracker.BundleTracker;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

public class MediatorActivator implements BundleActivator
{

  /**
   * Stores the currently registered Service Provider implementations for one
   * Service Type.
   *
   * <p>The object is shared with {@code ServiceLoader} instances. Provider
   * registrations can only be modified through {@link #putProviders(long, Set)}
   * and {@link #removeProviderBundle(long)}. The Provider map exposed to callers is
   * read-only.</p>
   *
   * <p>The generation is incremented whenever a Provider bundle for this
   * Service Type is registered, removed, updated, or begins stopping. This
   * lets mediated ServiceLoader instances invalidate only cached objects for
   * the affected Service Type.</p>
   */
  private static final class RegisteredServiceType
  {
    private final Map<Long, List<String>> providersByBundle_ = new HashMap<>();
    private final AtomicLong generation_ = new AtomicLong();

    RegisteredServiceType()
    {
    }

    /**
     * Records all services supplied by a bundle.
     *
     * @param bundleId the Provider bundle ID.
     * @param providers the fully qualified Provider implementation class names.
     */
    synchronized void putProviders(long bundleId,
                                   Set<String> providers)
    {
      List<String> implementations =
          Collections.unmodifiableList(new ArrayList<>(providers));
      providersByBundle_.put(bundleId, implementations);
      generation_.incrementAndGet();
    }

    /**
     * Removes all Provider implementations associated with a bundle.
     *
     * @param bundleId the Provider bundle ID.
     *
     * @return {@code true} if a registration was removed; {@code false} if the
     *         bundle had no registration for this Service Type.
     */
    synchronized boolean removeProviderBundle(long bundleId)
    {
      if (providersByBundle_.remove(Long.valueOf(bundleId)) == null)
        {
          return false;
        }
      generation_.incrementAndGet();
      return true;
    }

    synchronized void invalidateBundle(long bundleId)
    {
      if (providersByBundle_.containsKey(Long.valueOf(bundleId)))
        {
          generation_.incrementAndGet();
        }
    }

    long getGeneration()
    {
      return generation_.get();
    }

    /**
     * Copies the current provider registry into {@code destination} and
     * returns the generation belonging to that copy.
     *
     * <p>The copy and generation read are performed under the same monitor,
     * so callers cannot observe a registry from one generation paired with a
     * generation from another.</p>
     */
    synchronized long copyProvidersTo(Map<Long, List<String>> destination)
    {
      Objects.requireNonNull(destination, "destination");
      destination.clear();
      destination.putAll(providersByBundle_);
      return generation_.get();
    }
  }

  public static volatile MediatorActivator activator_;

  /** Logger used when the optional OSGi Log Service is not available. */
  private static final Logger NO_OP_LOGGER = createNoOpLogger();

  /** The current logger; defaults to a no-op logger until Log Service appears. */
  public static volatile Logger logger_ = NO_OP_LOGGER;

  /** Returns the bundle supplying the currently active mediator, if any. */
  static Bundle activatorBundle()
  {
    MediatorActivator activator = activator_;
    return activator == null || activator.bundleContext_ == null
        ? null
        : activator.bundleContext_.getBundle();
  }

  Bundle getMediatorBundle()
  {
    return bundleContext_ == null ? null : bundleContext_.getBundle();
  }

  private ServiceTracker<LoggerFactory, LoggerFactory> loggerFactoryTracker_;

  private BundleContext bundleContext_;

  @SuppressWarnings("rawtypes")
  private BundleTracker consumerBundleTracker_;
  @SuppressWarnings("rawtypes")
  private BundleTracker providerBundleTracker_;
  @SuppressWarnings("rawtypes")
  private ServiceRegistration weavingHookService_;

  private static final boolean debug_ = false;

  private final ConcurrentMap<String, RegisteredServiceType> registeredServiceTypes_ =
    new ConcurrentHashMap<>();

  private final ConcurrentMap<Bundle, Set<String>> consumerRequirements_ =
      new ConcurrentHashMap<>();

  private static final int CONSUMER_TRACKED_STATES = Bundle.RESOLVED |
                                                     Bundle.STARTING |
                                                     Bundle.ACTIVE;

  private static final int PROVIDER_TRACKED_STATES = Bundle.INSTALLED |
                                                     Bundle.RESOLVED |
                                                     Bundle.STARTING |
                                                     Bundle.ACTIVE |
                                                     Bundle.STOPPING;

  /**
   * <p>This method initializes the activator with the given bundle context and
   * starts the bundle trackers used to discover provider and consumer bundles.</p>
   *
   * <p>The provider tracker observes bundles in the {@code STARTING} and
   * {@code ACTIVE} states, so provider bundles can be inspected and registered
   * once they are starting or running.</p>
   *
   * <p>The consumer tracker observes bundles in the {@code RESOLVED},
   * {@code STARTING}, and {@code ACTIVE} states, so consumer
   * weaving metadata can be collected before consumer classes are loaded.</p>
   *
   * @param context the bundle context.
   */
  @SuppressWarnings({ "rawtypes", "unchecked" })
  public synchronized void start(BundleContext context) throws Exception
  {
    bundleContext_ = context;
    activator_ = this;
    loggerFactoryTracker_ = new ServiceTracker<>(context, LoggerFactory.class,
        new ServiceTrackerCustomizer<LoggerFactory, LoggerFactory>()
        {
          @Override
          public LoggerFactory addingService(
              ServiceReference<LoggerFactory> reference)
          {
            LoggerFactory factory = context.getService(reference);
            if (factory != null)
              {
                logger_ = getLogger(factory);
              }
            return factory;
          }

          @Override
          public void modifiedService(ServiceReference<LoggerFactory> reference,
                                      LoggerFactory factory)
          {
            logger_ = getLogger(factory);
          }

          @Override
          public void removedService(ServiceReference<LoggerFactory> reference,
                                     LoggerFactory factory)
          {
            context.ungetService(reference);
            logger_ = NO_OP_LOGGER;
          }
        });
    loggerFactoryTracker_.open();
    WeavingHook wh = new serviceLoaderWeavingHook(this);
    weavingHookService_ = context.registerService(WeavingHook.class.getName(), wh, null);

    providerBundleTracker_ = new BundleTracker(context,
                                               PROVIDER_TRACKED_STATES,
                                               new ServiceLoaderProviderTracker(this,
                                                                                   context.getBundle()));
    providerBundleTracker_.open();

    consumerBundleTracker_ = new BundleTracker(context,
                                               CONSUMER_TRACKED_STATES,
                                               new ServiceLoaderConsumerTracker(this));
    consumerBundleTracker_.open();
  }

  @Override
  public void stop(BundleContext context)
  {
    activator_ = null;
    weavingHookService_.unregister();
    consumerBundleTracker_.close();
    providerBundleTracker_.close();
    loggerFactoryTracker_.close();
    logger_ = NO_OP_LOGGER;
  }

  private static Logger createNoOpLogger()
  {
    return (Logger)Proxy.newProxyInstance(
        Logger.class.getClassLoader(),
        new Class<?>[] { Logger.class },
        (proxy, method, args) ->
        {
          if (method.getName().equals("getName"))
            {
              return "noop";
            }
          if (method.getReturnType() == boolean.class)
            {
              return false;
            }
          if (method.getReturnType() == String.class)
            {
              return "";
            }
          return null;
        });
  }

  private static Logger getLogger(LoggerFactory factory)
  {
    try
      {
        return Objects.requireNonNullElse(
            factory.getLogger(MediatorActivator.class), NO_OP_LOGGER);
      }
    catch (RuntimeException e)
      {
        return NO_OP_LOGGER;
      }
  }

  public void unregisterConsumerBundle(Bundle bundle)
  {
    consumerRequirements_.remove(bundle);
  }

  public void registerConsumerBundle(Bundle bundle, Set<String> serviceTypes)
  {
    consumerRequirements_.put(bundle, serviceTypes);
  }

  /**
   * Returns {@code true} if this bundle require ServiceLoader processing.
   */
  public boolean requiresProcessing(Bundle bundle)
  {
    return consumerRequirements_.containsKey(bundle);
  }

  /**
   * Records all Service Provider implementations supplied by a bundle for a
   * specific Service Type.
   *
   * @param serviceType the fully qualified name of the provided Service Type.
   * @param bundle the bundle supplying the Service Provider implementations.
   * @param providers the fully qualified names of the Provider implementation
   *        classes.
   */
  public void registerProviderBundle(String serviceType,
                                     Bundle bundle,
                                     Set<String> providers)
  {
    Objects.requireNonNull(serviceType, "serviceType");
    Objects.requireNonNull(bundle, "bundle");
    Objects.requireNonNull(providers, "providers");

    RegisteredServiceType registeredType =
        registeredServiceTypes_.computeIfAbsent(serviceType,
                                                ignored -> new RegisteredServiceType());

    registeredType.putProviders(bundle.getBundleId(), providers);
    logger_.info(String.format("Registered provider bundle %d of service Type  %s",
                 bundle.getBundleId(), serviceType));
    printDebug(String.format("Registered provider bundle %d of service Type  %s",
                              bundle.getBundleId(), serviceType));
  }

  /**
   * Removes all Service Provider registrations associated with a bundle.
   *
   * @param bundle the Provider bundle to remove.
   * @return {@code true} if the bundle was registered as a provider,
   *        {@code false} otherwise.
   */
  public boolean unregisterProviderBundle(Bundle bundle)
  {
    Objects.requireNonNull(bundle, "bundle");
    long bundleId = bundle.getBundleId();
    AtomicBoolean result = new AtomicBoolean();

    registeredServiceTypes_.forEach(
        (serviceType, registeredType) ->
        {
          if (registeredType.removeProviderBundle(bundleId))
            {
              result.set(true);
              logger_.info(String.format("Unregistered provider bundle %d for service Type  %s",
                          bundleId, serviceType));
              printDebug("Unregistered provider bundle "
                  + bundleId + " for service type "  + serviceType);
            }
        });
    return result.get();
  }

  /**
   * Invalidates cached provider instances when a registered provider bundle
   * begins stopping while retaining its mediator provider definitions. A later
   * ServiceLoader lookup can then start the bundle and create a fresh instance.
   *
   * @param bundle the provider bundle entering the stopping state.
   */
  public void providerBundleStopping(Bundle bundle)
  {
    Objects.requireNonNull(bundle, "bundle");
    long bundleId = bundle.getBundleId();
    registeredServiceTypes_.values().forEach(type -> type.invalidateBundle(bundleId));
  }

  /**
   * Returns the generation used by mediated ServiceLoader caches to detect
   * changes to one Service Type's provider registrations or lifecycle.
   *
   * @return the current provider generation.
   */
  public long getProviderGeneration(String serviceType)
  {
    Objects.requireNonNull(serviceType, "serviceType");
    RegisteredServiceType type = registeredServiceTypes_.get(serviceType);
    return type == null ? 0 : type.getGeneration();
  }

  /**
   * Copies the registered Provider information for a Service Type into the
   * supplied destination and returns the generation belonging to that copy.
   *
   * @param serviceType the fully qualified name of the requested Service Type.
   * @param destination the map to clear and populate.
   *
   * @return the provider generation, or {@code 0} if the Service Type has
   *         never been registered.
   */
  public long getRegisteredProviders(String serviceType,
                                     Map<Long, List<String>> destination)
  {
    Objects.requireNonNull(serviceType, "serviceType");
    Objects.requireNonNull(destination, "destination");

    RegisteredServiceType type = registeredServiceTypes_.get(serviceType);

    if (type == null)
      {
        destination.clear();
        return 0;
      }
    return type.copyProvidersTo(destination);
  }

  public Bundle getBundle(long bundleId)
  {
    return bundleContext_.getBundle(bundleId);
  }

  // for internal debug.
  public static void printDebug(String s)
  {
    if (debug_)
      {
        System.out.println(s);
      }
  }
}
