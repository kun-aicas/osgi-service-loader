/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader.Provider;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.BundleReference;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;

import com.aicas.osgi.spi.proxy.internal.MediatorActivator;

/**
 * Loads implementations of a service type through both the OSGi Service
 * Loader Mediator and the standard Java service-loading mechanism.
 *
 * <p>Each instance contains two provider-discovery paths:</p>
 * <ol>
 *   <li>a mediator-backed path that obtains provider bundle IDs and provider
 *       implementation-class names from the Service Loader Mediator.</li>
 *   <li>a Java delegate backed by {@link java.util.ServiceLoader};</li>
 * </ol>
 *
 * <p>The iterator returned by {@link #iterator()} consumes the mediator
 * iterator first and the Java delegate iterator second.</p>
 *
 * <p>The mediator path does not scan {@code META-INF/services}. It reads
 * provider registrations maintained by the mediator and loads provider classes
 * through the current {@link BundleWiring} of their provider bundles. Consumer
 * bundles are selected for weaving and registered with the mediator separately;
 * this class resolves the registered providers for the requested service type.</p>
 *
 * <p>The Java delegate is created according to the invoked load method:</p>
 * <ul>
 *   <li>{@link #load(Class)} uses
 *       {@link java.util.ServiceLoader#load(Class)}, and therefore uses the
 *       current thread context class loader.</li>
 *   <li>{@link #load(Class, ClassLoader)} normally preserves the
 *       supplied class loader by calling
 *       {@link java.util.ServiceLoader#load(Class, ClassLoader)}.</li>
 *   <li>If mediation is available and the supplied class loader implements
 *       {@link BundleReference}, OSGi providers are resolved by the mediator.
 *       In that case the Java delegate is created with
 *       {@link java.util.ServiceLoader#load(Class)} instead of retaining
 *       provider instances through the supplied bundle class loader. This
 *       reduces the possibility of returning cached providers from a stopped
 *       or updated bundle. Providers visible only through that bundle class
 *       loader must consequently be published to the mediator.</li>
 * </ul>
 *
 * <p>Mediator provider definitions are snapshotted when an iterator is created.
 * Provider instances are cached across iterators until {@link #reload()} is
 * called, the provider generation changes, or the mediator becomes
 * unavailable. An iterator rechecks the provider generation before returning
 * a prepared provider, so it does not return an instance prepared before a
 * provider stop, update, or removal.</p>
 *
 * <p>If the mediator is unavailable, the mediator iterator is empty and the
 * returned loader uses only its Java delegate.</p>
 *
 * @param <S> the service type
 */
public class ServiceLoader<S> implements Iterable<S>
{
  /*----------------------- classes and enums -------------------------*/
  /**
   * Identifies one Provider implementation supplied by one bundle.
   */
  private static final class ProviderDefinition
  {
    private final BundleRevision bundleRev;
    private final String className;

    ProviderDefinition(BundleRevision bundleRevision,
                       String className)
    {
      this.bundleRev = bundleRevision;
      this.className = Objects.requireNonNull(className, "className");
    }

    @Override
    public boolean equals(Object object)
    {
      if (this == object)
        {
          return true;
        }

      if (!(object instanceof ProviderDefinition))
        {
          return false;
        }

      ProviderDefinition other = (ProviderDefinition)object;

      return bundleRev.equals(other.bundleRev) &&
             className.equals(other.className);
    }

    @Override
    public int hashCode()
    {
      return 31 * bundleRev.hashCode() +
             className.hashCode();
    }
  }

  /**
   * The Provider class is loaded when {@link #type()} is first called and
   * instantiated when {@link #get()} is called.
   */
  private final class ProviderImpl<S> implements Provider<S>
  {
    private final ProviderDefinition definition;
    private Class<? extends S> providerClass;

    ProviderImpl(ProviderDefinition definition)
    {
      this.definition = definition;
    }

    @SuppressWarnings({ "unchecked" })
    @Override
    public Class<? extends S> type()
    {
      if (providerClass == null)
        {
          providerClass = (Class<? extends S>)loadProviderClass(definition);
        }
      return providerClass;
    }

    @Override
    public S get()
    {
      Class<? extends S> providerType = type();
      try
        {
          Constructor<? extends S> constructor =
            providerType.getConstructor();

          if (!Modifier.isPublic(constructor.getModifiers()))
            {
              throw fail("Provider " + providerType.getName() +
                         " does not have a public no-argument constructor");
            }

          return constructor.newInstance();
        }
      catch (NoSuchMethodException e)
        {
          throw fail("Provider " + providerType.getName() +
                     " does not have a public no-argument constructor",
                     e);
        }
      catch (InstantiationException e)
        {
          throw fail("Provider " + providerType.getName() +
                     " could not be instantiated",
                     e);
        }
      catch (IllegalAccessException e)
        {
          throw fail("Provider constructor is not accessible: " +
                     providerType.getName(),
                     e);
        }
      catch (InvocationTargetException e)
        {
          Throwable cause =  e.getCause() == null ? e : e.getCause();

          throw fail("Provider constructor failed: " + providerType.getName(),
                     cause);
        }
      catch (ExceptionInInitializerError e)
        {
          throw fail("Provider initialization failed: " +
                     providerType.getName(),
                     e);
        }
    }
  }

  private final class CombinedIterator implements Iterator<S>
  {
    private final Iterator<S>[] iterators;
    private final MediatorServiceIterator mediatorIterator;
    /** Provider classes already returned by this combined iterator. */
    private final Set<Class<?>> seenProviderClasses =
        Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());
    /** Candidate prepared by hasNext() for return from next(). */
    private S nextProvider;
    /** Class prepared by the mediator iterator but not yet retrieved from it. */
    private Class<?> preparedMediatorProviderClass;
    private int iteratorIndex = 0;

    /**
     * Creates a combined iterator.
     *
     * @param iterators the provider iterators to consume in order
     */
    CombinedIterator(Iterator<S>[] iterators,
                     MediatorServiceIterator mediatorIterator)
    {
      this.iterators = iterators;
      this.mediatorIterator = mediatorIterator;
    }

    @Override
    public boolean hasNext()
    {
      if (preparedMediatorProviderClass != null)
        {
          return true;
        }
      if (nextProvider != null)
        {
          return true;
        }

      while (iteratorIndex < iterators.length)
        {
          Iterator<? extends S> iterator = iterators[iteratorIndex];
          if (iterator == mediatorIterator)
            {
              if (!mediatorIterator.hasNext())
                {
                  iteratorIndex++;
                  continue;
                }
              S candidate = mediatorIterator.getPreparedProvider();
              if (seenProviderClasses.add(candidate.getClass()))
                {
                  preparedMediatorProviderClass = candidate.getClass();
                  return true;
                }
              mediatorIterator.next();
              continue;
            }
          if (iterator != null && iterator.hasNext())
            {
              S candidate = iterator.next();

              if (seenProviderClasses.add(candidate.getClass()))
                {
                  MediatorActivator.printDebug("[ITERATOR] check in the " +
                                  (iteratorIndex == 0 ? "osgiIterator"
                                                      : "javaIterator"));
                  nextProvider = candidate;
                  return true;
                }

              MediatorActivator.printDebug("[ITERATOR] skip duplicate provider " +
                                           candidate.getClass().getName());
              continue;
            }
          iteratorIndex++;
        }
      return false;
    }

    @Override
    public S next()
    {
      if (!hasNext())
        {
          throw new NoSuchElementException();
        }

      if (preparedMediatorProviderClass != null)
        {
          Class<?> preparedClass = preparedMediatorProviderClass;
          preparedMediatorProviderClass = null;
          S result = mediatorIterator.next();
          if (result.getClass() != preparedClass)
            {
              seenProviderClasses.remove(preparedClass);
              seenProviderClasses.add(result.getClass());
            }
          return result;
        }
      S result = nextProvider;
      nextProvider = null;
      return result;
    }

  }

  /**
   * Iterates over an immutable snapshot of Provider definitions.
   *
   * <p>The snapshot is captured when {@link ServiceLoader#iterator()} is
   * called and rebuilt if the provider generation changes before the iterator
   * returns a prepared provider.</p>
   *
   * <p>Before a provider is instantiated, the availability of its provider
   * bundle is checked. A provider whose bundle is no longer available is
   * skipped.</p>
   *
   * <p>The next valid Provider instance is prepared by
   * {@link #hasNext()} and returned by {@link #next()}.</p>
   */
  private final class MediatorServiceIterator implements Iterator<S>
  {
    /**
     */
    private ProviderDefinition[] definitionsArray;

    /** Provider generation used to create {@link #definitionsArray}. */
    private long providerGeneration;

    /**
     * Index of the next Provider definition to examine.
     */
    private int definitionIndex = 0;

    /**
     * Provider instance prepared by hasNext().
     */
    private S nextProvider;

    /**
     * Creates an iterator over a provider-definition and provider-cache
     * snapshot.
     *
     * @param providers the provider-definition snapshot.
     */
    MediatorServiceIterator(ProviderDefinition[] providers,
                            long providerGeneration)
    {
      this.definitionsArray = providers;
      this.providerGeneration = providerGeneration;
    }

    /**
     * Rebuilds this iterator's snapshot when a provider lifecycle or registry
     * change occurred after the iterator was created.
     */
    private void refreshSnapshotIfProviderGenerationChanged()
    {
      MediatorActivator activator = MediatorActivator.activator_;
      if (activator == null)
        {
          definitionsArray = new ProviderDefinition[0];
          definitionIndex = 0;
          nextProvider = null;
          providerGeneration = Long.MIN_VALUE;
          return;
        }

      long currentGeneration = activator.getProviderGeneration(serviceName);
      if (currentGeneration != providerGeneration)
        {
          Set<ProviderDefinition> definitions = new LinkedHashSet<>();
          long snapshotGeneration =
              getCurrentProviderDefinitions(activator, definitions);
          definitionsArray = definitions.toArray(ProviderDefinition[]::new);
          definitionIndex = 0;
          nextProvider = null;
          providerGeneration = snapshotGeneration;
        }
    }

    /**
     * Searches for and prepares the next usable provider.
     *
     * <p>If a provider has already been prepared by an earlier
     * {@link #hasNext()} call, this method returns without advancing the
     * definition index. Providers unavailable when a new instance would be
     * created, and providers that fail to load or instantiate, are skipped.</p>
     *
     * <p>A failing OSGi provider is isolated from its consumer: a
     * {@link ServiceConfigurationError} raised while loading or instantiating
     * one mediator provider is logged and ignored, and iteration continues
     * with the next provider. This OSGi-specific policy prevents one faulty
     * provider bundle from blocking the consumer or other healthy providers.
     * Errors raised by the Java {@link java.util.ServiceLoader} delegate are
     * handled by that delegate and are not subject to this policy.</p>
     *
     * <p>Mediator providers are deliberately loaded and instantiated while
     * preparing {@code hasNext()}. A stopped OSGi provider bundle is normally
     * started by {@link #createProviderIfAvailable(ProviderDefinition)} before
     * the provider is loaded. The relevant race is that the bundle can be
     * uninstalled, updated, or otherwise become unable to start before the
     * provider is created; preparing the instance first lets {@code next()}
     * return a provider already known to be usable, rather than discovering a
     * provider failure only after {@code hasNext()} reported success. This
     * also permits the iterator to skip unavailable or failing providers
     * before reporting that another provider exists. Instances already handed
     * to consumer code cannot be revoked if their provider bundle later stops.</p>
     *
     * @return {@code true} if a provider has been prepared; otherwise
     *         {@code false}
     */
    private boolean prepareNextProvider()
    {
      refreshSnapshotIfProviderGenerationChanged();
      if (nextProvider != null)
        {
          return true;
        }

      while (nextProvider == null &&
             definitionIndex < definitionsArray.length)
        {
          MediatorActivator.printDebug("[SERVICE_ITERATOR] prepare next Provider of index" +
                          definitionIndex);
          ProviderDefinition definition = definitionsArray[definitionIndex++];
          try
            {
              S provider = createProviderIfAvailable(definition);
              if (provider != null)
                {
                  nextProvider = provider;
                  return true;
                }
            }
          catch (ServiceConfigurationError e)
            {
              MediatorActivator.logger_.warn("Failed to load service provider "
                                                   + definition.className
                                                   + " for service type "
                                                   + serviceName
                                                   + " from bundle "
                                                   + definition.bundleRev.getSymbolicName(),
                                               e);
            }
        }
      return false;
    }

    /**
     * Retrieves a cached provider instance or creates a new instance.
     *
     * <p>A provider stop invalidates the mediated cache. If no fresh instance
     * exists, an inactive provider bundle is started before its provider class
     * is loaded.</p>
     *
     * <p>Invalid provider declarations and instantiation failures result in a
     * {@link ServiceConfigurationError}. The mediator iterator logs that error
     * and continues with the next provider so that a faulty OSGi provider does
     * not block its consumer.</p>
     *
     * @param definition the Provider definition.
     *
     * @return the Provider instance.
     *         {@code null} if the provider bundle cannot be started.
     */
    private S createProviderIfAvailable(ProviderDefinition definition)
    {
      CompletableFuture<S> providerFuture;
      boolean shouldCreateProvider = false;
      synchronized (providerCacheLock)
        {
          providerFuture = cachedProviders.get(definition);
          if (providerFuture == null)
            {
              providerFuture = new CompletableFuture<>();
              cachedProviders.put(definition, providerFuture);
              shouldCreateProvider = true;
            }
        }

      if (shouldCreateProvider)
        {
          createProvider(definition, providerFuture);
        }
      else if (providerCreations_.get().contains(definition))
        {
          // The current thread is already constructing this provider and the
          // cached future is not complete yet. Joining it here would make the
          // thread wait forever for its own provider construction to finish.
          throw fail("Recursive creation of provider " + definition.className);
        }

      return getProvider(providerFuture);
    }

    /** Creates one provider without holding the global provider-cache lock. */
    private void createProvider(ProviderDefinition definition,
                                CompletableFuture<S> providerFuture)
    {
      Set<ProviderDefinition> providerCreations = providerCreations_.get();
      if (!providerCreations.add(definition))
        {
          providerFuture.completeExceptionally(
              fail("Recursive creation of provider " + definition.className));
          removeCachedProvider(definition, providerFuture);
          return;
        }

      try
        {
          Bundle bundle = definition.bundleRev.getBundle();
          if (!isProviderBundleAvailable(bundle))
          {
            try
              {
                bundle.start();
              }
            catch (BundleException | SecurityException | IllegalStateException e)
              {
                MediatorActivator.logger_.warn(
                    "Failed to start the provider bundle", e);
                providerFuture.complete(null);
                removeCachedProvider(definition, providerFuture);
                return;
              }
          }
          Provider<S> provider = new ProviderImpl<>(definition);
          providerFuture.complete(provider.get());
        }
      catch (Throwable e)
        {
          providerFuture.completeExceptionally(e);
          removeCachedProvider(definition, providerFuture);
        }
      finally
        {
          providerCreations.remove(definition);
          if (providerCreations.isEmpty())
            {
              providerCreations_.remove();
            }
        }
    }

    /** Waits for a provider being constructed for the same definition. */
    private S getProvider(CompletableFuture<S> providerFuture)
    {
      try
        {
          return providerFuture.join();
        }
      catch (CompletionException e)
        {
          Throwable cause = e.getCause();
          if (cause instanceof ServiceConfigurationError)
            {
              throw (ServiceConfigurationError)cause;
            }
          if (cause instanceof RuntimeException)
            {
              throw (RuntimeException)cause;
            }
          if (cause instanceof Error)
            {
              throw (Error)cause;
            }
          throw fail("Provider construction failed", cause);
        }
    }

    @SuppressWarnings({ "removal", "deprecation" })
    @Override
    public boolean hasNext()
    {
      if (acc == null)
        {
          return prepareNextProvider();
        }
      return AccessController
          .doPrivileged((PrivilegedAction<Boolean>) this::prepareNextProvider,
                        acc);
    }

    /**
     * Returns the Provider instance prepared by {@link #hasNext()}.
     *
     * <p>The provider generation is checked again through
     * {@link #prepareNextProvider()}. A provider prepared before a stop,
     * update, or removal is discarded and the current snapshot is used.</p>
     */
    private S nextPreparedProvider()
    {
      if (!prepareNextProvider())
        {
          throw new NoSuchElementException(" no provider any more");
        }
      S result = nextProvider;
      nextProvider = null;
      return result;
    }

    /** Returns the instance currently prepared by {@link #hasNext()}. */
    private S getPreparedProvider()
    {
      if (nextProvider == null)
        {
          throw new IllegalStateException("No provider has been prepared");
        }
      return nextProvider;
    }

    @SuppressWarnings({ "removal", "deprecation"})
    @Override
    public S next()
    {
      if (acc == null)
        {
          return nextPreparedProvider();
        }
      return AccessController.doPrivileged((PrivilegedAction<S>) this::nextPreparedProvider,
                                           acc);
    }

    @Override
    public void remove()
    {
      throw new UnsupportedOperationException();
    }
  }
  /*--------------------------- variables -----------------------------*/
  /**
   * The Service Type being loaded.
   */
  private final Class<S> service;

  /**
   * The fully qualified Service Type name.
   */
  private final String serviceName;

  /**
   * Java service loader used for providers visible through the ordinary Java
   * service-loading mechanism.
   */
  private final java.util.ServiceLoader<S> javaDelegateServiceLoader;

  /** Whether this loader should append providers discovered by the mediator. */
  private final boolean mediatorEnabled;

  /**
   * Provider instances cached for this {@code ServiceLoader} instance and
   * returned from the OSGi iterator.
   *
   * <p>The cache key identifies both the provider bundle revision and the
   * provider implementation class. Consequently, an instance is reused by
   * later iterators created from the same loader, but an updated provider
   * bundle revision receives a new cache entry. The cache is intentionally not
   * shared between different {@code ServiceLoader} instances, matching the
   * cache scope of {@link java.util.ServiceLoader}.</p>
   *
   * <p>{@link #reload()} clears this cache. A provider generation change also
   * clears it, including when a provider bundle begins stopping. Consequently,
   * later mediated lookups create a fresh instance after starting the provider
   * bundle when necessary. Instances already returned to consumer code remain
   * outside the mediator's lifecycle control.</p>
   */
  private final Map<ProviderDefinition, CompletableFuture<S>> cachedProviders =
      new HashMap<>();

  /**
   * Definitions currently being created by the calling thread.
   *
   * <p>{@link ThreadLocal#withInitial(java.util.function.Supplier)} creates
   * the set lazily the first time {@link ThreadLocal#get()} is called on a
   * thread. A definition is added while its provider is being constructed and
   * removed when construction finishes. This detects same-thread recursive
   * creation without treating concurrent construction by another thread as
   * recursion.</p>
   */
  private final ThreadLocal<Set<ProviderDefinition>> providerCreations_ =
      ThreadLocal.withInitial(HashSet::new);
  /**
   * Coordinates cache invalidation with provider-cache operations.
   */
  private final Object providerCacheLock = new Object();

  /** Provider generation represented by {@link #cachedProviders}. */
  private long cachedProviderGeneration = Long.MIN_VALUE;
  /**
   * Access control context captured when this loader was created.
   */
  @SuppressWarnings("removal")
  private final AccessControlContext acc;

  /*------------------------  constructors  ---------------------------*/

  /**
   * Creates a combined Java-and-mediator service loader.
   *
   * @param service the service type
   * @param serviceLoader the Java service loader delegate
   *
   * @throws NullPointerException if {@code service} is {@code null}
   */
  @SuppressWarnings({ "removal", "deprecation" })
  private ServiceLoader(Class<S> service,
                        java.util.ServiceLoader<S> serviceLoader)
  {
    this(service, serviceLoader, true);
  }

  /**
   * Creates a service loader with explicit mediator participation.
   *
   * @param service the service type
   * @param serviceLoader the Java service loader delegate
   * @param mediatorEnabled whether OSGi mediator providers are included
   */
  @SuppressWarnings({ "removal", "deprecation" })
  private ServiceLoader(Class<S> service,
                        java.util.ServiceLoader<S> serviceLoader,
                        boolean mediatorEnabled)
  {
    this.service = Objects.requireNonNull(service, "service");
    this.serviceName = service.getName();
    this.javaDelegateServiceLoader = serviceLoader;
    this.mediatorEnabled = mediatorEnabled;
    this.acc = System.getSecurityManager() == null? null
                                                   : AccessController.getContext();
  }

  /*---------------------------- methods ------------------------------*/

  /**
   * Creates a service loader for the specified service type.
   *
   * @param service the service type.
   * @param <S> the service type.
   *
   * @return a service loader.
   *
   * @throws NullPointerException if {@code service} is {@code null}.
   * @throws SecurityException if the consumer bundle does not have permission
   *         to obtain the requested service
   */
  public static <S> ServiceLoader<S> load(Class<S> service)
  {
    Objects.requireNonNull(service, "service");

    if (MediatorActivator.activator_ == null)
      {
        System.err.println("Service Loader Mediator is unavailable; " +
                           "using the Java ServiceLoader delegate.");
      }

    java.util.ServiceLoader<S> javaDelegate = java.util.ServiceLoader.load(service);
    return new ServiceLoader<>(service, javaDelegate);
  }

  /**
   * Creates a service loader for the specified service type using the supplied
   * class loader.
   *
   * <p>If the mediator is unavailable, this method delegates directly to
   * {@link java.util.ServiceLoader#load(Class, ClassLoader)}.</p>
   *
   * <p>If mediation is available and {@code requestedLoader} is an OSGi bundle
   * class loader, OSGi providers are discovered by the mediator. In that case,
   * the Java delegate is created using
   * {@link java.util.ServiceLoader#load(Class)}, rather than retaining the
   * explicitly supplied bundle class loader.</p>
   *
   * <p>For a non-OSGi class loader, the supplied loader is passed unchanged to
   * the Java ServiceLoader. A {@code null} loader is also passed through to the
   * Java API and may therefore result in a {@link NullPointerException}.</p>
   *
   * @param service the service type.
   * @param requestedLoader the class loader requested by the consumer, or
   *        {@code null}.
   * @param <S> the service type.
   *
   * @return a service loader.
   *
   * @throws NullPointerException if {@code service} or, when used by the Java
   *         delegate, {@code requestedLoader} is {@code null}.
   */
  public static <S> ServiceLoader<S> load(Class<S> service,
                                          ClassLoader requestedLoader)
  {
    Objects.requireNonNull(service, "service");
    Objects.requireNonNull(requestedLoader, "requestedLoader");

    java.util.ServiceLoader<S> javaDelegate;

    if (MediatorActivator.activator_ == null)
      {
        System.err.println("Service Loader Mediator is unavailable; "
            + "using the Java ServiceLoader delegate.");
        javaDelegate = java.util.ServiceLoader.load(service, requestedLoader);
      }
    else if (requestedLoader instanceof BundleReference)
      {
        /*
         * OSGi bundle providers are resolved by the mediator. Do not retain
         * provider instances through the explicitly supplied bundle class
         * loader.
         */
        javaDelegate = java.util.ServiceLoader.load(service);
      }
    else
      {
        /* Preserve the semantics of the original Java ServiceLoader call for
         * null and non-OSGi class loaders.
         */
       javaDelegate = java.util.ServiceLoader.load(service, requestedLoader);
      }
    return new ServiceLoader<>(service, javaDelegate);
  }

  /**
   * Determines whether a provider bundle can currently be used to create a
   * provider instance.
   *
   * @param bundle the provider bundle.
   *
   * @return {@code true} if the bundle is active or starting; otherwise
   *         {@code false}.
   */
  private static boolean isProviderBundleAvailable(Bundle bundle)
  {
    int state = bundle.getState();
    return state == Bundle.ACTIVE ||
           state == Bundle.STARTING;
  }

  /**
   * Returns the provider definitions currently registered with the mediator.
   *
   * <p>Provider definitions that are still registered and have a cached
   * provider instance are returned first. Cached definitions that are no
   * longer registered are removed from the cache. Newly registered
   * definitions are then appended in mediator order.</p>
   *
   * <p>The returned set is a snapshot for the iterator being created; later
   * registry changes do not affect that iterator.</p>
   *
   * @param activator the active service-loader mediator.
   *
   * @param destination the set to clear and populate with provider definitions.
   *
   * @return the provider generation belonging to the populated destination.
   */
  private long getCurrentProviderDefinitions(
      MediatorActivator activator,
      Set<ProviderDefinition> destination)
  {
    Objects.requireNonNull(destination, "destination");

    // Resolve the mediator snapshot before taking the local-cache lock. Bundle
    // lookup and revision adaptation may enter framework code.
    Map<Long, List<String>> registeredProviders = new HashMap<>();
    long currentGeneration = activator.getRegisteredProviders(serviceName,
                                                              registeredProviders);
    List<ProviderDefinition> definitions;
    if (registeredProviders.isEmpty())
      {
        MediatorActivator.logger_.debug("no registered providers of service type " +
                                        serviceName);
        definitions = List.of();
      }
    else
      {
        definitions = fetchProviderDefinitions(activator, registeredProviders);
      }

    Set<ProviderDefinition> definitionSet = new LinkedHashSet<>(definitions);

    synchronized (providerCacheLock)
      {
        if (cachedProviderGeneration != currentGeneration)
          {
            cachedProviders.clear();
            cachedProviderGeneration = currentGeneration;
          }

        Set<ProviderDefinition> availableProviders = new LinkedHashSet<>();

        // give the cached providers the priority to be used.
        Iterator<ProviderDefinition> cachedProviderIterator =
            cachedProviders.keySet().iterator();
        while (cachedProviderIterator.hasNext())
          {
            ProviderDefinition def = cachedProviderIterator.next();
            if (definitionSet.contains(def))
              {
                availableProviders.add(def);
              }
            else
              {
                // remove the old cached definition.
                cachedProviderIterator.remove();
              }
          }
        MediatorActivator.printDebug("[ITERATOR] found " + availableProviders.size() + " cached services.");
        availableProviders.addAll(definitionSet);
        MediatorActivator.printDebug("[ITERATOR] found " + availableProviders.size() + " services.");
        destination.clear();
        destination.addAll(availableProviders);
        return currentGeneration;
      }
  }

  /** Removes an entry only when it still represents the supplied future. */
  private void removeCachedProvider(ProviderDefinition definition,
                                    CompletableFuture<S> providerFuture)
  {
    synchronized (providerCacheLock)
      {
        cachedProviders.remove(definition, providerFuture);
      }
  }

  /** Clears the mediated provider-instance cache and its generation marker. */
  private void clearMediatorCache()
  {
    synchronized (providerCacheLock)
      {
        cachedProviders.clear();
        cachedProviderGeneration = Long.MIN_VALUE;
      }
  }

  /**
   * Builds a list of provider definitions from the current mediator registry.
   * Each bundle's implementation names originate from a set in the registry,
   * so the registry snapshot cannot contain duplicate bundle/implementation
   * pairs.
   *
   * @param activator the active mediator.
   *
   * @param registeredProviders provider implementation-class names grouped by
   *        provider bundle ID.
   *
   * @return the provider definitions visible to the consumer.
   */
  private List<ProviderDefinition> fetchProviderDefinitions(MediatorActivator activator,
                                                            Map<Long, List<String>> registeredProviders)
  {
    List<ProviderDefinition> result = new ArrayList<>();
    for (Long bundleId : registeredProviders.keySet())
      {
        Bundle providerBundle = activator.getBundle(bundleId);
        if (providerBundle != null)
          {
            BundleRevision rev = providerBundle.adapt(BundleRevision.class);
            if (rev == null)
            {
              continue;
            }
            List<String> implementationNames = registeredProviders.get(bundleId);
            if (implementationNames != null)
              {
                for (String implementationName : implementationNames)
                  {
                    result.add(new ProviderDefinition(rev,
                                                      implementationName));
                  }
              }
          }
      }
    MediatorActivator.printDebug("[ITERATOR] found " + result.size() + " services.");
    return result;
  }

  /**
   * Loads and validates a provider implementation class through the current
   * wiring of its provider bundle.
   *
   * @param definition the provider definition.
   *
   * @return the provider implementation class as a subclass of the requested
   *         service type.
   *
   * @throws ServiceConfigurationError if the provider bundle is unavailable,
   *         has no current class loader, the class cannot be loaded, or the
   *         class is not assignable to the requested service type
   */
  private Class<? extends S> loadProviderClass(ProviderDefinition definition)
  {
    Bundle providerBundle = definition.bundleRev.getBundle();

    if (!isProviderBundleAvailable(providerBundle))
      {
        throw fail("Provider bundle is no longer available");
      }

    ClassLoader providerLoader = getBundleClassLoader(providerBundle);
    if (providerLoader == null)
      {
        throw fail("Provider bundle " + definition.bundleRev +
                   " has no current bundle class loader");
      }

    final Class<?> providerClass;
    try
      {
        providerClass = Class.forName(definition.className,
                                      false,
                                      providerLoader);
        MediatorActivator.printDebug("[SERVICE_ITERATOR] got provider " + providerClass.getName());
      }
    catch (ClassNotFoundException e)
      {
        throw fail(definition.className + " was not found", e);
      }
    catch (LinkageError e)
      {
        throw fail(definition.className + " from bundle " +
                   definition.bundleRev + " could not be loaded",
                   e);
      }

    if (!service.isAssignableFrom(providerClass))
      {
        throw fail("Provider " + definition.className + " from bundle " +
                   definition.bundleRev + " is not a subtype of " + serviceName);
      }
    return providerClass.asSubclass(service);
  }

  /**
   * Returns an iterator over providers visible through the Java delegate and
   * the mediator.
   *
   * <p>The returned iterator combines the provider sources in this order:</p>
   * <ol>
   *   <li>the mediator iterator.</li>
   *   <li>the Java {@link java.util.ServiceLoader} delegate iterator; and</li>
   * </ol>
   *
   * <p>If the mediator is unavailable, only Java delegate providers are returned.</p>
   *
   * @return an iterator over Java and mediator-provided provider instances
   */
  @SuppressWarnings("unchecked")
  @Override
  public Iterator<S> iterator()
  {
    MediatorActivator activator = MediatorActivator.activator_;
    Iterator<S>[] iteratorArray;
    MediatorServiceIterator osgiIterator = null;
    Iterator<S> javaIterator = javaDelegateServiceLoader.iterator();

    if (!mediatorEnabled || activator == null)
      {
        if (mediatorEnabled)
          {
            clearMediatorCache();
          }
        if (activator == null && mediatorEnabled)
          {
            System.err.println("Service Loader Mediator is unavailable, " +
                               "using the Java ServiceLoader delegate.");
          }
        iteratorArray = new Iterator[]{javaIterator };
      }
    else
      {
        Set<ProviderDefinition> definitions = new LinkedHashSet<>();
        long providerGeneration =
            getCurrentProviderDefinitions(activator, definitions);
        ProviderDefinition[] definitionArray =
            definitions.toArray(ProviderDefinition[]::new);
        osgiIterator = new MediatorServiceIterator(definitionArray,
                                                   providerGeneration);
        iteratorArray = new Iterator[]{ osgiIterator, javaIterator };
      }
    return new CombinedIterator(iteratorArray, osgiIterator);
  }

  /**
   * Clears both the Java delegate cache and the mediator cache.
   *
   * <p>Iterators created before this method is called should no longer be
   * used. The next call to {@link #iterator()} reloads Java providers and
   * rereads the mediator registry.</p>
   */
  public void reload()
  {
    javaDelegateServiceLoader.reload();
    clearMediatorCache();
  }

  /**
   * Returns the first provider currently available to this loader.
   *
   * @return an {@link Optional} containing the first provider, or
   *         {@link Optional#empty()} if no provider is available
   */
  public Optional<S> findFirst()
  {
    Iterator<S> iterator = iterator();
    if (iterator.hasNext())
      {
        return Optional.of(iterator.next());
      }
    else
      {
        return Optional.empty();
      }
  }

  /**
   * Creates a Java-only service loader using the system class loader.
   *
   * <p>This method deliberately bypasses the OSGi mediator. A newly created
   * {@code ServiceLoader} has no cached OSGi provider instances, and
   * {@code loadInstalled} must not populate that cache from the mediator's
   * global provider registry. It therefore delegates only to
   * {@link java.util.ServiceLoader} with the system class loader and returns
   * Java-installed providers only.</p>
   *
   * @param service service type
   * @param <S> service type
   * @return a service loader
   */
  public static <S> ServiceLoader<S> loadInstalled(Class<S> service)
  {
    Objects.requireNonNull(service, "service");

    java.util.ServiceLoader<S> javaDelegate =
        java.util.ServiceLoader.load(service, ClassLoader.getSystemClassLoader());
    return new ServiceLoader<>(service, javaDelegate, false);
  }

  /**
   * Returns a diagnostic string containing this loader's service type.
   *
   * @return the loader class name and service type
   */
  @Override
  public String toString()
  {
    return ServiceLoader.class.getName() + "[" + serviceName + "]";
  }

  /**
   * Creates a service-configuration error for this loader's service type.
   */
  private ServiceConfigurationError fail(String message)
  {
    return new ServiceConfigurationError(serviceName + ": " + message);
  }
  /**
   * Creates a service-configuration error for this loader's service type.
   */
  private ServiceConfigurationError fail(String message,
                                         Throwable cause)
  {
    return new ServiceConfigurationError(serviceName + ": " + message,  cause);
  }
  /**
   * Creates a service-configuration error for this loader's service type.
   */
  private static ClassLoader getBundleClassLoader(Bundle bundle)
  {
    BundleWiring wiring = bundle.adapt(BundleWiring.class);

    return wiring == null ? null : wiring.getClassLoader();
  }
}
