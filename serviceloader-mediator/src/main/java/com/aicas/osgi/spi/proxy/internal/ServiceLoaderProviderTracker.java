/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import static org.osgi.framework.wiring.BundleRevision.TYPE_FRAGMENT;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.module.ModuleDescriptor;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.ServicePermission;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.util.tracker.BundleTrackerCustomizer;


/**
 * Tracks Service Loader provider metadata over a bundle's lifecycle.
 *
 * <ul>
 *   <li>{@link #addingBundle(Bundle, BundleEvent)} reads provider metadata for
 *       each host bundle, registers discovered providers with the mediator, and
 *       registers required OSGi services when the host is already active.</li>
 *   <li>{@link #modifiedBundle(Bundle, BundleEvent, ProviderBundleInfo)}
 *       registers or unregisters OSGi services for start and stop events. It
 *       also refreshes mediator metadata when either the host revision or its
 *       attached-fragment revision set changes. The refresh removes and
 *       rediscovers providers declared by both {@code module-info.class} and
 *       {@code osgi.serviceloader} capability metadata.</li>
 *   <li>{@link #removedBundle(Bundle, BundleEvent, ProviderBundleInfo)} removes
 *       remaining OSGi services and all mediator provider definitions for the
 *       bundle.</li>
 * </ul>
 */
public class ServiceLoaderProviderTracker implements
BundleTrackerCustomizer<ServiceLoaderProviderTracker.ProviderBundleInfo>
{

  /*----------------------- classes and enums -------------------------*/

  enum RegisterMode
  {
    NONE, // No Service Providers are registered as OSGi services.
    ALL, //  All advertised providers of the Service Type are registered.
    SINGLE // Only the specified provider implementation is registered.
  }

  enum CapabilityMetadataResult
  {
    /**
     * No usable {@code osgi.serviceloader} capability was found, so module
     * metadata may be used as a fallback.
     */
    NO_METADATA,
    /**
     * Valid capability metadata was declared, but no provider was registered.
     */
    METADATA_DECLARED,
    /**
     * Valid capability metadata was declared and at least one provider was
     * registered.
     */
    PROVIDERS_REGISTERED
  }

  /**
   * Tracks the provider metadata associated with one host bundle.
   *
   * <p>An instance is retained for every non-fragment bundle, including bundles
   * that expose providers only through {@code module-info.class} or that do not
   * currently expose any providers. This lets the tracker detect a new bundle
   * revision or a change to attached fragments and then remove and rediscover
   * the mediator's provider definitions. OSGi service registrations are only
   * one optional part of the tracked metadata.</p>
   */
  static class ProviderBundleInfo
  {
    private List<BundleRevision> revisions_;
    private final List<ServiceRegistrationInfo> serviceRegistrationInfos_ =
        new ArrayList<>();
    private List<ProviderCapability> provideCapabilities_;

    ProviderBundleInfo(List<BundleRevision> revisions,
                       List<ServiceRegistrationInfo> registrations,
                       List<ProviderCapability> provideCapabilities)
    {
      this.revisions_ = List.copyOf(revisions);
      serviceRegistrationInfos_.addAll(registrations);
      provideCapabilities_ = new ArrayList<>(provideCapabilities);
    }

    boolean hasSameRevisions(List<BundleRevision> revisions)
    {
      return revisions_.equals(revisions);
    }

    void updateFrom(ProviderBundleInfo replacement)
    {
      revisions_ = replacement.revisions_;
      serviceRegistrationInfos_.clear();
      serviceRegistrationInfos_.addAll(replacement.serviceRegistrationInfos_);
      provideCapabilities_ = new ArrayList<>(replacement.provideCapabilities_);
    }

    void registerOsgiServices(Bundle bundle, Bundle spiBundle)
    {
      for (ServiceRegistrationInfo info : serviceRegistrationInfos_)
        {
          info.registerService(bundle, spiBundle, provideCapabilities_);
        }
    }

    void unregisterOsgiServices()
    {
      for (ServiceRegistrationInfo info : serviceRegistrationInfos_)
        {
          info.unregister();
        }
    }
  }

  static class ServiceRegistrationInfo
  {
    String serviceType_;
    String serviceProvider_;
    List<ServiceRegistration> registrations_ = new ArrayList<>();

   /**
    * @param serviceType the fully qualified name of the provided Service Type.
    * @param serviceProvider the fully qualified name of the Provider
    *                        implementation class.
    */
    ServiceRegistrationInfo(String serviceType,
                            String serviceProvider)
    {
      this.serviceType_ = serviceType;
      this.serviceProvider_ = serviceProvider;
    }

    /**
     * Registers a discovered Service Provider as an OSGi service when required by
     * a matching {@code osgi.serviceloader} capability.
     *
     * <p>The method examines all supplied capabilities advertising the requested
     * Service Type and creates a registration for each matching capability whose
     * register mode selects the Provider implementation. The caller checks the
     * provider's registrar extender wire before invoking this method.</p>
     *
     * @param bundle the bundle containing the Service Provider implementation.
     */
    void registerService(Bundle bundle,
                         Bundle spiBundle,
                         List<ProviderCapability> provideCapabilities)
    {
      /*
       * Prevent duplicate registration when multiple lifecycle events are
       * received for the same active period.
       */
      if (!registrations_.isEmpty())
        {
          return;
        }
      for (ProviderCapability p : provideCapabilities)
        {
          if (!p.getServiceType().equals(serviceType_))
            {
              continue;
            }
          if (p.getRegisterMode() == RegisterMode.ALL ||
              (p.getRegisterMode() == RegisterMode.SINGLE &&
               serviceProvider_.equals(p.getSelectedProvider())))
            {
              // register OSGI service
              ServiceRegistration reg = registerOSGIService(bundle, spiBundle,
                                                            p.getProperties());
              if (reg != null)
                {
                  registrations_.add(reg);
                }
            }
        }
    }

    /**
     * Registers this object's Provider implementation as an OSGi service.
     *
     * <p>When a {@link SecurityManager} is active, the Provider bundle must have
     * {@link ServicePermission#REGISTER} permission for the specified Service Type.
     * If the permission is missing, no service is registered.</p>
     *
     * @param bundle the bundle containing the Provider implementation and in whose
     *               bundle context the service is registered.
     * @param spiBundle the Service Loader mediator bundle.
     * @param properties the service properties to associate with the registration,
     *
     * @return the created OSGi service registration, or {@code null} if registration fails
     */
    private ServiceRegistration registerOSGIService(Bundle bundle,
                                                    Bundle spiBundle,
                                                    Hashtable<String, Object> properties)
    {
      BundleContext context = bundle.getBundleContext();
      if (context == null)
        {
          return null;
        }
      try
        {
          final Class<?> cls = bundle.loadClass(serviceProvider_);
          Object instance = new ProviderServiceFactory(cls);

          Hashtable<String, Object> registrationProperties =
              new Hashtable<>(properties);

          registrationProperties.put(MediatorConstants.SERVICELOADER_MEDIATOR_PROPERTY,
                         spiBundle.getBundleId());

          if (System.getSecurityManager() != null &&
              !bundle.hasPermission(new ServicePermission(serviceType_,
                                                          ServicePermission.REGISTER)))
            {
              MediatorActivator.logger_.warn("Does not have the permission to register services of type: " +
                      serviceType_);
              return null;
            }
          ServiceRegistration  reg = context.registerService(serviceType_,
                                        instance,
                                        registrationProperties);

          MediatorActivator.printDebug("[PROVIDER_TRACKER] register OSGI Service " + serviceType_ + " - " +
                  serviceProvider_ + " " + registrationProperties.toString());

          return reg;
        }
      catch (ClassNotFoundException e)
        {
          MediatorActivator.logger_.warn("Could not load provider " + serviceProvider_ +
                                 " of service " + serviceType_,
                                 e);
          return null;
        }
    }

    void unregister()
    {
      for (ServiceRegistration reg : registrations_)
        {
          try
            {
              MediatorActivator.printDebug("[PROVIDER_TRACKER] unregister OSGI Service " + reg.toString());
              reg.unregister();
            }
          catch (IllegalStateException ise)
            {
              // Ignore the exception but do not remove the try/catch.
              // There are some bundle context races on cleanup which
              // are safe to ignore but unsafe not to perform our own
              // cleanup. In an ideal world ServiceRegistration.unregister()
              // would have been idempotent and never throw an exception.
            }
        }
      registrations_.clear();
    }
  }

  /**
   * Describes an {@code osgi.serviceloader} capability declared by a Service
   * Provider bundle.
   *
   * <p>The metadata identifies the advertised Service Type, the capability
   * attributes that may be used as OSGi service properties, and the registration
   * behavior defined by the capability's {@code register} directive.</p>
   *
   * <p>The registration behavior is represented by {@link RegisterMode}:</p>
   *
   * <ul>
   *   <li>{@link RegisterMode#NONE}: no Provider implementation is registered as
   *       an OSGi service.</li>
   *   <li>{@link RegisterMode#ALL}: all Provider implementations associated with
   *       the advertised Service Type are registered.</li>
   *   <li>{@link RegisterMode#SINGLE}: only the Provider implementation named by
   *       {@link #getSelectedProvider()} is registered.</li>
   * </ul>
   *
   * <p>The selected Provider class is therefore non-{@code null} only when the
   * registration mode is {@link RegisterMode#SINGLE}. For the other modes, it is
   * {@code null}.</p>
   *
   * <p>Instances of this class are immutable. The service properties are copied
   * when returned by {@link #getProperties()}, preventing callers from modifying
   * the internally stored metadata.</p>
   */
  static class ProviderCapability
  {
    private final Hashtable<String, Object> properties_;
    private final String serviceType_;

    /**
    * The fully qualified provider implementation class selected by the
    * {@code register} directive.
    *
    * <p>This field is non-null only when {@link RegisterMode} is
    * {@link RegisterMode#SINGLE}.</p>
    */
    private final String selectedProvider_;

    private final RegisterMode registerMode_;

    /**
     * Creates metadata for an {@code osgi.serviceloader} capability.
     *
     * @param serviceType the advertised Service Type.
     *
     * @param selectedProvider the selected Provider class for {@link RegisterMode#SINGLE},
     *                         or {@code null} otherwise.
     *
     * @param registerMode the Provider registration mode.
     *
     * @param properties the capability attributes used as service properties.
     *
     */
    ProviderCapability(String serviceType,
                       String selectedProvider,
                       RegisterMode registerMode,
                       Hashtable<String, Object> properties)
    {
      this.serviceType_ = serviceType;
      this.properties_ = properties;
      this.registerMode_ = registerMode;
      this.selectedProvider_ = selectedProvider;
    }

    /**
     * Returns the service properties declared by the capability.
     *
     * @return the service properties.
     */
    public Hashtable<String, Object> getProperties()
    {
      return new Hashtable<String, Object>(properties_);
    }

    /**
     * Returns the Service Type advertised by the capability.
     *
     * @return the fully qualified Service Type name.
     */
    public String getServiceType()
    {
      return serviceType_;
    }

    /**
     * Returns the Provider class selected by the {@code register} directive.
     *
     * @return the fully qualified Provider class name, or {@code null} if no
     *         individual Provider is selected.
     */
    public String getSelectedProvider()
    {
      return selectedProvider_;
    }

    /**
     * Returns how Providers associated with this capability should be registered.
     *
     * @return the Provider registration mode.
     */
    public RegisterMode getRegisterMode()
    {
      return registerMode_;
    }

    @Override
    public String toString()

    {
      String registration;
      switch (registerMode_)
        {
          case ALL:
            registration = "all";
            break;

          case SINGLE:
            registration = selectedProvider_;
            break;

          default:
            registration = "none";
            break;
        }
      return String.format("ProviderCapability [serviceType=\"%s\", registration=%s, properties=%s]",
              serviceType_,
                           registration,
              properties_);

    }
  }

  /*--------------------------- variables -----------------------------*/

  final MediatorActivator activator_;
  public final Bundle spiBundle_;

  /*------------------------  constructors  ---------------------------*/
  public ServiceLoaderProviderTracker(MediatorActivator activator, Bundle spiBundle)
  {
    this.activator_ = activator;
    this.spiBundle_ = spiBundle;
  }

  /*---------------------------- methods ------------------------------*/
  /**
   * Processes a newly tracked bundle as a potential Service Loader Provider
   * bundle.
   *
   * The method ignores the SPI API bundle itself and fragment bundles.
   *
   * @param bundle the bundle being added to the tracker.
   * @param event the bundle event that caused the bundle to be added; may be
   *              {@code null}, depending on the tracker invocation.
   *
   * @return a {@link ProviderBundleInfo} used to track the provider metadata
   *         for every host bundle, including module-info-only and currently
   *         metadata-free bundles; {@code null} only when the bundle is the SPI
   *         bundle itself or a fragment. Tracking these hosts lets the mediator
   *         detect bundle revision, fragment attachment, and fragment removal.
   */
  @Override
  public ProviderBundleInfo addingBundle(Bundle bundle,
                                         BundleEvent event)
  {

    if (event!=null)
      {
        MediatorActivator.printDebug("[PROVIDER_TRACKER] addingBundle[" +
                        bundle.getBundleId() + "] " +
                        ServiceLoaderProviderTracker.getState(event.getType()));
      }
    ProviderBundleInfo info = readMetadata(bundle);

    if (info != null && bundle.getState() == Bundle.ACTIVE)
      {
        registerOsgiServicesIfRegistrarWired(bundle, info);
      }
    return info;
  }

  private ProviderBundleInfo readMetadata(Bundle bundle)
  {
    BundleRevision bundleRevision = bundle.adapt(BundleRevision.class);
    if (bundle.equals(spiBundle_) ||
        ((bundleRevision != null) &&
         ((bundleRevision.getTypes() & TYPE_FRAGMENT) == TYPE_FRAGMENT)))
      {
//        MediatorActivator.printDebug("[PROVIDER_TRACKER] Don't process SPI bundle itself and fragment bundles.");
        return null;
      }

    List<ServiceRegistrationInfo> serviceRegistrations = new ArrayList<>();
    List<ProviderCapability> provideCapabilities = new ArrayList<>();
    List<BundleRevision> revisions =
        HeaderProcessor.getHostAndFragmentRevisions(bundle);
    // get osgi service loader metadata
    CapabilityMetadataResult metadataResult =
        readServiceLoaderMediatorCapabilityMetadata(bundle, revisions,
                                                    serviceRegistrations,
                                                    provideCapabilities);
    boolean moduleInfoProviders = false;

    // if the osgi.serviceloader capabilities metadata not present,
    // fallback to module-info file.
    if (metadataResult == CapabilityMetadataResult.NO_METADATA)
      {
        try
          {
            moduleInfoProviders = readModuleInfoProviderMetadata(bundle);
            if (!moduleInfoProviders)
              {
                MediatorActivator.printDebug("[PROVIDER_TRACKER] no providers in bundle " +
                    bundle.getBundleId());
              }
            serviceRegistrations.clear();
          }
        catch (IOException e)
          {
            MediatorActivator.logger_.warn("Could not read module provider metadata from bundle " +
                                             bundle.getBundleId(),
                                             e);
          }
      }
    return new ProviderBundleInfo(revisions,
                                  serviceRegistrations,
                                  provideCapabilities);
  }
  /**
   * Reads Java module provider declarations from the specified bundle and
   * registers the discovered providers with the Service Loader mediator.
   *
   * <p>The method reads the bundle's {@code module-info.class} and processes
   * every {@code provides ... with ...} declaration from its
   * {@link ModuleDescriptor}. Providers discovered through module metadata are
   * registered only in the mediator's provider registry. They are not registered
   * as OSGi services.</p>
   *
   * <p>For example, the following module declaration:</p>
   *
   * <pre>{@code
   * module example.provider {
   *   provides com.example.Service
   *       with com.example.internal.ServiceImpl;
   * }
   * }</pre>
   *
   * <p>causes {@code ServiceImpl} to be registered as a mediated provider for
   * {@code com.example.Service}.</p>
   *
   * @param bundle the bundle whose {@code module-info.class} is to be inspected
   * @return {@code true} if at least one provider declaration was registered;
   *         {@code false} if no module descriptor or provider declaration was
   *         found, or if the descriptor could not be read
   * @throws IOException
   */
  private boolean readModuleInfoProviderMetadata(Bundle bundle) throws IOException
  {
    URL moduleInfoURL = bundle.getEntry(MediatorConstants.MODULE_INFO);
    if (moduleInfoURL == null)
      {
        return false;
      }
    final ModuleDescriptor descriptor;
    try (InputStream input = moduleInfoURL.openStream())
      {
        descriptor = ModuleDescriptor.read(input);
      }
    catch (IOException e)
        {
          throw new IOException("Could not read module descriptor from bundle " +
                                bundle.getBundleId() + ": " +
                                moduleInfoURL,
                                e);
        }

    boolean providersRegistered = false;

    for (ModuleDescriptor.Provides provides : descriptor.provides())
      {
        String serviceType = provides.service();
        Set<String> providers = new LinkedHashSet<>(provides.providers());

        if (providers.isEmpty())
          {
            continue;
          }

        activator_.registerProviderBundle(serviceType,
                                         bundle,
                                         providers);
        providersRegistered = true;
        MediatorActivator.printDebug("[PROVIDER_TRACKER] Found SPI provider: serviceType=" +
            serviceType + ", providers=" +
            providers.toString());
      }
    return providersRegistered;
  }


  /**
   * For each host bundle, it collects the host revision together with its
   * attached fragment revisions, reads the declared
   * {@code osgi.serviceloader} Provider metadata, and determines whether the
   * Service Loader Registrar extender is enabled.
   *
   * <p>The method then locates the relevant
   * {@code META-INF/services/<service-type>} resources in the bundle. Every
   * valid Provider implementation class discovered in a configuration file is
   * passed to mediator registration and, when required by the corresponding
   * capability, OSGi service registration.</p>
   *
   * <p>Every provider implementation discovered in a service configuration is
   * registered with the mediator. The {@code registrations} list is populated
   * only for implementations that a matching capability requires to be
   * registered as an OSGi service: {@link RegisterMode#ALL} includes every
   * implementation, while {@link RegisterMode#SINGLE} includes only its
   * selected implementation. Actual OSGi registration is deferred until the
   * provider is active and its registrar extender wire has been verified.</p>
   *
   * @param bundle
   * @param registrations the destination list for provider entries that require
   * OSGi service registration; it is not populated for mediator-only providers.
   *
   * @return the outcome of reading the {@code osgi.serviceloader} metadata. The
   *         caller falls back to {@code module-info.class} only when no
   *         {@code osgi.serviceloader} capability metadata is declared;
   *         metadata remains authoritative when it was found, even if it
   *         declares no provider configuration file.
   */
  private CapabilityMetadataResult readServiceLoaderMediatorCapabilityMetadata(
      Bundle bundle,
      List<BundleRevision> revisions,
      List<ServiceRegistrationInfo> registrations,
      List<ProviderCapability> provideCapabilities)
  {
    if (revisions.isEmpty())
      {
        return CapabilityMetadataResult.NO_METADATA;
      }
    /*
     * Build the complete registration plan once. Actual OSGi registration is
     * still gated on the resolver wire at the lifecycle point where it occurs.
     */
    Set<String> providedServices = HeaderProcessor
        .readServiceLoaderProviderMetadata(revisions, provideCapabilities);
    if (providedServices == null)
      {
        // MediatorActivator.printDebug("[PROVIDER_TRACKER] No provided SPI service types in metadata " +
        // bundle.getBundleId());
        return CapabilityMetadataResult.NO_METADATA;
      }

    Set<URL> serviceFileURLs = getServiceFileUrls(bundle, providedServices);

    if (serviceFileURLs.isEmpty())
      {
        MediatorActivator.logger_.warn("No provider Configuration files under " +
                  "META-INF/services/ for bundle " + bundle.getBundleId());
        return CapabilityMetadataResult.METADATA_DECLARED;
      }

    Map<String, Set<String>> providersByServiceType = new HashMap<String, Set<String>>();
    for (URL serviceFileURL : serviceFileURLs)
      {
        // MediatorActivator.printDebug("[PROVIDER_TRACKER] Found SPI resource: " + serviceFileURL);
        String serviceType = getServiceType(serviceFileURL);
        if (serviceType == null)
          {
            MediatorActivator.logger_.warn("Cannot determine service type from " +
                      serviceFileURL);
            continue;
          }

        try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(serviceFileURL.openStream(),
                                                   StandardCharsets.UTF_8));)
          {
            String line;
            Set<String> providers =
              providersByServiceType.computeIfAbsent(serviceType,
                                                     ignored -> new HashSet<String>());
            while ((line = reader.readLine()) != null)
              {
                String implementationClassName = getServiceProvider(line);
                if (implementationClassName == null)
                  {
                    continue;
                  }

                MediatorActivator.printDebug("[PROVIDER_TRACKER] Found SPI " +
                    "provider: serviceType=" + serviceType + ", implementation=" +
                     implementationClassName);

                if (providers.add(implementationClassName) &&
                    requiresOsgiServiceRegistration(serviceType,
                                                    implementationClassName,
                                                    provideCapabilities))
                  {
                    registrations.add(new ServiceRegistrationInfo(serviceType,
                                                                  implementationClassName));
                  }
              }
          }
        catch (IOException e)
          {
            MediatorActivator.logger_ .warn(" Could not read SPI metadata from " +
                      serviceFileURL, e);
          }
      }

    boolean providersRegistered = false;
    for (Map.Entry<String, Set<String>> entry : providersByServiceType.entrySet())
      {
        if (entry.getValue().isEmpty())
          {
            continue;
          }
        activator_.registerProviderBundle(entry.getKey(),
                                          bundle,
                                          entry.getValue());
        providersRegistered = true;
      }

    return providersRegistered ? CapabilityMetadataResult.PROVIDERS_REGISTERED :
                                 CapabilityMetadataResult.METADATA_DECLARED;
  }

  private boolean requiresOsgiServiceRegistration(String serviceType,
                                                   String provider,
                                                   List<ProviderCapability> capabilities)
  {
    for (ProviderCapability capability : capabilities)
      {
        if (!serviceType.equals(capability.getServiceType()))
          {
            continue;
          }
        if (capability.getRegisterMode() == RegisterMode.ALL ||
            (capability.getRegisterMode() == RegisterMode.SINGLE &&
             provider.equals(capability.getSelectedProvider())))
          {
            return true;
          }
      }
    return false;
  }

  /**
   * Parses and validates a single Service Provider declaration.
   *
   * The method removes an optional comment introduced by {@code '#'},
   * normalizes the remaining text, and validates that it represents a
   * syntactically valid fully qualified Java class name.</p>
   *
   * @param line one line read from a Service Provider configuration file.
   *
   * @return the normalized Provider class name, or {@code null} if the line is
   *  blank, contains only a comment, or contains an invalid Provider
   *  class name
   **/
  private String getServiceProvider(String line)
  {
    int commentIndex = line.indexOf('#');
    if (commentIndex >= 0)
      {
        line = line.substring(0, commentIndex);
      }
    line = HeaderProcessor.normalize(line);
    if (line == null)
      {
        return null;
      }
    if ((line.indexOf(' ') >= 0) || (line.indexOf('\t') >= 0))
      {
        // A Service Provider declaration must contain exactly one class name.
        // Embedded spaces or tabs would indicate either multiple names or
        // malformed syntax.
        MediatorActivator.logger_.error("Illegal configuration-file syntax: " + line);
        return null;
      }
    if (!isValidProviderClassName(line))
      {
        MediatorActivator.logger_.error("Illegal provider-class name: " + line);
        return null;
      }
    return line;
  }

  /**
   * Validates a provider configuration entry as a Java binary class name.
   * Each dot must separate two non-empty Java identifier segments; the
   * identifier rules also allow binary nested-class names such as
   * {@code example.Outer$Inner}.
   */
  private boolean isValidProviderClassName(String name)
  {
    boolean atSegmentStart = true;
    for (int index = 0; index < name.length();)
      {
        int codePoint = name.codePointAt(index);
        if (codePoint == '.')
          {
            if (atSegmentStart)
              {
                return false;
              }
            atSegmentStart = true;
          }
        else if (atSegmentStart)
          {
            if (!Character.isJavaIdentifierStart(codePoint))
              {
                return false;
              }
            atSegmentStart = false;
          }
        else if (!Character.isJavaIdentifierPart(codePoint))
          {
            return false;
          }
        index += Character.charCount(codePoint);
      }
    return !atSegmentStart;
  }

  private String getServiceType(URL serviceFileURL)
  {
    String serviceFile = serviceFileURL.toExternalForm();
    int idx = serviceFile.lastIndexOf('/');
    if (idx < 0 || idx == serviceFile.length())
      {
        return null;
      }
    return serviceFile.substring(idx + 1);
  }

  /**
   * Finds ServiceLoader provider configuration files for explicitly declared SPI
   * service types.
   *
   * <p>Only {@code META-INF/services/<serviceType>} files whose service type is
   * declared through {@code Provide-Capability: osgi.serviceloader} are returned.
   * For each declared service type, the method asks the framework to resolve the
   * corresponding resource through {@link Bundle#getResources(String)}. The
   * framework applies the bundle's effective {@code Bundle-ClassPath}, which may
   * include resources in embedded JARs; this method does not inspect embedded
   * JAR files directly.</p>
   *
   * @param bundle the provider bundle whose ServiceLoader configuration files
   *        should be located.
   *
   * @param serviceTypes the SPI service types declared by
   *        {@code Provide-Capability: osgi.serviceloader}.
   *
   * @return a set of URLs for matching {@code META-INF/services/<serviceType>}
   *         files resolved by the framework through the bundle class path.
   */
  private Set<URL> getServiceFileUrls(Bundle bundle, Set<String> serviceTypes)
  {
    Set<URL> serviceFileURLs = new HashSet<URL>();
    for (String serviceType : serviceTypes)
      {
        String serviceFilePath = MediatorConstants.METAINF_SERVICES + "/" + serviceType;
        try
          {
            Enumeration<URL> resources = bundle.getResources(serviceFilePath);
            if (resources != null)
              {
                serviceFileURLs.addAll(Collections.list(resources));
              }
          }
        catch (IOException e)
          {
            MediatorActivator.logger_.error("Failed to read " + serviceFilePath +
                                  " in bundle " + bundle.getSymbolicName(),
                                  e);
          }
      }
    return serviceFileURLs;
  }


  @Override
  public void modifiedBundle(Bundle bundle,
                             BundleEvent event,
                             ProviderBundleInfo info)
  {
    if (info == null)
      {
        return;
      }
    /*
     * Update may install a new revision while the bundle remains within the
     * BundleTracker state mask. Fragment attachment changes the host wiring
     * without necessarily changing the host revision, so compare the complete
     * host-and-fragment revision set as well.
     */
    List<BundleRevision> currentRevisions = HeaderProcessor.getHostAndFragmentRevisions(bundle);
    if (!info.hasSameRevisions(currentRevisions))
      {
        refreshProviderMetadata(bundle, info);
      }

    if (event != null)
      {
        int state =  event.getType();
        switch (state)
        {
          case BundleEvent.STARTED:
            // regist osgi service;
            registerOsgiServicesIfRegistrarWired(bundle, info);
            break;

          case BundleEvent.STOPPING:
            // unregist osgi service;
            info.unregisterOsgiServices();
            activator_.providerBundleStopping(bundle);
            break;

          default:
            break;
        }
      }
  }

  /** Registers the precomputed OSGi-service plan only when wired to this registrar. */
  private void registerOsgiServicesIfRegistrarWired(Bundle bundle,
                                                    ProviderBundleInfo info)
  {
    List<BundleRevision> revisions = HeaderProcessor.getHostAndFragmentRevisions(bundle);
    // The single-cardinality rule applies to the Consumer processor extender.
    // Registrar processing does not impose that restriction in this helper.
    if (!HeaderProcessor.hasMediatorExtenderWire(revisions,
                                                 HeaderProcessor.PROVIDER_EXTENDER_REGISTRAR,
                                                 spiBundle_, false))
      {
        return;
      }
    info.registerOsgiServices(bundle, spiBundle_);
  }

  private void refreshProviderMetadata(Bundle bundle, ProviderBundleInfo info)
  {
    //Clean up runtime services created from the old revision.
    info.unregisterOsgiServices();

    //Remove providers discovered from the old revision.
    activator_.unregisterProviderBundle(bundle);

    //Analyse the new revision.
    ProviderBundleInfo newInfo = readMetadata(bundle);

    if (newInfo != null)
      {
        info.updateFrom(newInfo);

        //The bundle may already be active when the new revision is observed.
        if (bundle.getState() == Bundle.ACTIVE)
          {
            registerOsgiServicesIfRegistrarWired(bundle, info);
          }
      }
  }

  @Override
  public void removedBundle(Bundle bundle, BundleEvent event,
                            ProviderBundleInfo info)
  {
    if (event != null)
      {
        MediatorActivator.printDebug("[PROVIDER_TRACKER] removedBundle[" +
                        bundle.getBundleId() + "] " +
                        ServiceLoaderProviderTracker.getState(event.getType()));
      }

    if (info != null)
      {
        info.unregisterOsgiServices();
      }
    activator_.unregisterProviderBundle(bundle);
  }

  // for debug
  public static String getState(int state)
  {
    switch(state)
    {
      case BundleEvent.INSTALLED: return "INSTALLED";
      case BundleEvent.LAZY_ACTIVATION: return "LAZY_ACTIVATION";
      case BundleEvent.RESOLVED: return "RESOLVED";
      case BundleEvent.STARTED: return "STARTED";
      case BundleEvent.STARTING: return "STARTING";
      case BundleEvent.STOPPED: return "STOPPED";
      case BundleEvent.UNINSTALLED: return "UNINSTALLED";
      case BundleEvent.STOPPING: return "STOPPING";
      case BundleEvent.UNRESOLVED: return "UNRESOLVED";
      case BundleEvent.UPDATED: return "UPDATED";
      default: return "UNKONWN state" + state;
    }
  }

}
