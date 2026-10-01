/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import static com.aicas.osgi.spi.proxy.internal.HeaderProcessor.getHostAndFragmentRevisions;
import static org.osgi.framework.wiring.BundleRevision.TYPE_FRAGMENT;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleDescriptor;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.util.tracker.BundleTrackerCustomizer;

/**
 * Tracks Service Loader provider metadata over a bundle's lifecycle.
 *
 * <ul>
 *   <li>{@link #addingBundle(Bundle, BundleEvent)} reads provider metadata for
 *       each host bundle, registers discovered providers with the mediator, and
 *       registers matching OSGi services when the host is already active and
 *       wired to this mediator's registrar extender.</li>
 *   <li>{@link #modifiedBundle(Bundle, BundleEvent, OsgiServiceEntry)}
 *       registers or unregisters OSGi services for start and stop events. It
 *       registers services only when the provider is wired to this mediator's
 *       registrar extender.</li>
 *   <li>{@link #removedBundle(Bundle, BundleEvent, OsgiServiceEntry)} removes
 *       remaining OSGi services and all mediator provider definitions for the
 *       bundle.</li>
 * </ul>
 */
public class ServiceLoaderProviderTracker implements
BundleTrackerCustomizer<OsgiServiceEntry>
{

  /*----------------------- classes and enums -------------------------*/

  /*--------------------------- variables -----------------------------*/

  final MediatorActivator activator_;
  public final Bundle spiBundle_;

  /*------------------------  constructors  ---------------------------*/
  /**
   * Creates a provider tracker for one mediator and its SPI Bundle.
   *
   * @param activator the active mediator
   * @param spiBundle the Bundle that provides the mediator registrar extender
   */
  public ServiceLoaderProviderTracker(MediatorActivator activator,
                                      Bundle spiBundle)
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
   * @return an {@link OsgiServiceEntry} used to manage OSGi service
   *         registrations, or {@code null} when the bundle is the SPI bundle,
   *         a fragment, or scanning produces no provider metadata entries.
   */
  @Override
  public OsgiServiceEntry addingBundle(Bundle bundle,
                                       BundleEvent event)
  {
    if (event!=null)
      {
        MediatorActivator.printDebug("[PROVIDER_TRACKER] addingBundle[" +
                        bundle.getBundleId() + "] " +
                        ServiceLoaderProviderTracker.getState(event.getType()));
      }

    BundleRevision bundleRevision = bundle.adapt(BundleRevision.class);
    if (bundle.equals(spiBundle_) ||
        ((bundleRevision != null) &&
         ((bundleRevision.getTypes() & TYPE_FRAGMENT) == TYPE_FRAGMENT)))
      {
        return null;
      }

    // scan the bundle for service types, either through configuration files or module-info.class
    Map<String, Set<String>> impByType = new LinkedHashMap<>();
    scanServices(bundle, impByType);
    if (impByType.isEmpty())
      {
        return null;
      }

    // Parse osgi.serviceloader capabilities.
    List<ProviderCapability> capabilities =
      createServiceLoaderCapabilities(bundle);

    OsgiServiceEntry osgiServiceEntry = new OsgiServiceEntry(impByType,
                                                             capabilities);

    if (!capabilities.isEmpty())
      {
        for (ProviderCapability capability : capabilities)
          {
            activator_.registerServiceproviderCapabilities(bundle, capability);
          }
      }

    Set<ProviderEntry> serviceLoaderEntries =
      createServiceProviderEntries(bundle, impByType);

    if (!serviceLoaderEntries.isEmpty())
      {
        for (ProviderEntry entry : serviceLoaderEntries)
          {
            activator_.registerServiceProviderEntries(bundle, entry);
          }
      }

    if (bundle.getState() == Bundle.ACTIVE &&
        !capabilities.isEmpty())
      {
        registerOsgiServicesIfRegistrarWired(bundle, osgiServiceEntry);
      }
    return osgiServiceEntry;
  }

  /** Registers the precomputed OSGi-service plan only when wired to this registrar. */
  private void registerOsgiServicesIfRegistrarWired(Bundle bundle,
                                                    OsgiServiceEntry entry)
  {
    List<BundleRevision> revisions = getHostAndFragmentRevisions(bundle);
    // The single-cardinality rule applies to the Consumer processor extender.
    // Registrar processing does not impose that restriction in this helper.
    if (!HeaderProcessor.hasMediatorExtenderWire(revisions,
                                                 MediatorConstants.REGISTRAR_EXTENDER_NAME,
                                                 spiBundle_, false))
      {
        return;
      }
    entry.registerOsgiServices(bundle, spiBundle_);
  }

  private void scanServiceDir(Enumeration<URL> serviceFileURLs,
                              Map<String, Set<String>> impByType)
  {
    while (serviceFileURLs.hasMoreElements())
      {
        URL serviceFileURL = serviceFileURLs.nextElement();
        // MediatorActivator.printDebug("[PROVIDER_TRACKER] Found SPI resource: " + serviceFileURL);
        String serviceType = getServiceType(serviceFileURL);
        if (serviceType == null)
          {
            MediatorActivator.logger_.warn("Cannot determine service " +
                                           "type from " + serviceFileURL);
            continue;
          }

        try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(serviceFileURL.openStream(),
                                                   StandardCharsets.UTF_8));)
          {
            String line;
            Set<String> providers =
              impByType.computeIfAbsent(serviceType,
                                        ignored -> new LinkedHashSet<String>());
            while ((line = reader.readLine()) != null)
              {
                String implementationClassName = getServiceProvider(line);
                if (implementationClassName == null)
                  {
                    continue;
                  }

                MediatorActivator.printDebug("[PROVIDER_TRACKER] Found SPI " +
                                             "provider: serviceType=" +
                                             serviceType + ", implementation=" +
                                             implementationClassName);
                providers.add(implementationClassName);
              }
          }
        catch (IOException e)
          {
            MediatorActivator.logger_.warn(" Could not read SPI metadata from " +
                      serviceFileURL, e);
          }
      }
  }

  /**
   * Collects Java module provider declarations from the specified bundle.
   *
   * <p>The method reads the bundle's {@code module-info.class} and processes
   * every {@code provides ... with ...} declaration from its
   * {@link ModuleDescriptor}. The discovered providers are added to
   * {@code impByType}, together with any providers found in
   * {@code META-INF/services}. The caller later registers the resulting
   * provider entries with the mediator.</p>
   *
   * <p>For example, the following module declaration:</p>
   * <pre>{@code
   * module example.provider {
   *   provides com.example.Service
   *       with com.example.internal.ServiceImpl;
   * }
   * }</pre>
   *
   * <p>adds {@code ServiceImpl} as a provider for
   * {@code com.example.Service}.</p>
   *
   * @param bundle the bundle whose {@code module-info.class} is to be inspected
   * @param impByType the collected implementations, grouped by service type
   */
  private void scanModuleInfo(Bundle bundle,
                              Map<String, Set<String>> impByType)
  {
    URL moduleInfoURL = bundle.getEntry(MediatorConstants.MODULE_INFO);
    if (moduleInfoURL == null)
      {
        return;
      }
    final ModuleDescriptor descriptor;
    try (InputStream input = moduleInfoURL.openStream())
      {
        descriptor = ModuleDescriptor.read(input);
        for (ModuleDescriptor.Provides provides : descriptor.provides())
          {
            String serviceType = provides.service();
            Set<String> providers = new LinkedHashSet<>(provides.providers());
            if (providers.isEmpty())
              {
                continue;
              }
            impByType.computeIfAbsent(serviceType,
                                      ignored -> new LinkedHashSet<>()).addAll(providers);
            MediatorActivator.printDebug("[PROVIDER_TRACKER] Found SPI provider:" +
                                         " serviceType=" + serviceType +
                                         ", providers=" + providers.toString());
          }
      }
    catch (IOException | InvalidModuleDescriptorException e)
      {
        MediatorActivator.logger_.warn("Could not read %s of bundle %s: %s",
                                       moduleInfoURL.getPath(),
                                       bundle.getSymbolicName(), e);
      }
  }

  private void scanServices(Bundle bundle, Map<String, Set<String>> impByType)
  {
    // host plus attached fragments; the bundle is at least RESOLVED,
    // so this does not resolve anything
    Enumeration<URL> files =
      bundle.findEntries(MediatorConstants.METAINF_SERVICES, "*", false);
    if (files != null)
      {
        scanServiceDir(files, impByType);
      }
    scanModuleInfo(bundle, impByType);
  }


  private List<ProviderCapability> createServiceLoaderCapabilities(Bundle bundle)
  {
    List<ProviderCapability> serviceLoaderCapabilities = new ArrayList<>();
    for (BundleRevision revision : getHostAndFragmentRevisions(bundle))
      {
        List<BundleCapability> capabilities =
          revision.getDeclaredCapabilities(MediatorConstants.SERVICELOADER_CAPABILITY_NAMESPACE);
        for (BundleCapability cap : capabilities)
          {
            Object serviceTypeValue = cap.getAttributes().
                          get(MediatorConstants.SERVICELOADER_CAPABILITY_NAMESPACE);

            // get the service type.
            String serviceType =
              HeaderProcessor.normalize((String) serviceTypeValue);
            if (serviceType == null)
              {
                MediatorActivator.logger_.error("Invalid osgi.serviceloader " +
                                                "capability " + cap);
                continue;
              }
            MediatorActivator.printDebug("[PROVIDER_PROCESSOR] Found service " +
                                         "type: " + serviceType);
            serviceLoaderCapabilities.add(new ProviderCapability(serviceType,
                                                                 cap,
                                                                 bundle));
          }
      }
    return serviceLoaderCapabilities;
  }

  private Set<ProviderEntry> createServiceProviderEntries(Bundle bundle,
                                                          Map<String, Set<String>> impByType)
  {
    BundleWiring wiring = bundle.adapt(BundleWiring.class);
    if (wiring == null)
      {
        return Set.of();
      }
    Map<String, BundleCapability> packageCapability = new LinkedHashMap<>();
    Set<ProviderEntry> serviceProviderEntries = new LinkedHashSet<>();
    for (Map.Entry<String, Set<String>> entry : impByType.entrySet())
      {
        String serviceType = entry.getKey();
        String pkg = PackageWiringUtil.packageOf(serviceType);
        if (pkg.isEmpty())
          {
            continue;
          }
        if (!packageCapability.containsKey(pkg))
          {
            BundleCapability capability =
              PackageWiringUtil.effectivePackageCapability(bundle, pkg);
            packageCapability.put(pkg, capability);
          }
        serviceProviderEntries.add(new ProviderEntry(serviceType,
                                                     bundle,
                                                     packageCapability.get(pkg),
                                                     entry.getValue()));
      }
    return serviceProviderEntries;
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

  @Override
  public void modifiedBundle(Bundle bundle,
                             BundleEvent event,
                             OsgiServiceEntry info)
  {
    if (info == null)
      {
        return;
      }
    if (event != null)
      {
        int state = event.getType();
        switch (state)
          {
          case BundleEvent.STARTED:
            // regist osgi service;
            registerOsgiServicesIfRegistrarWired(bundle, info);
            break;

          case BundleEvent.STOPPING:
            // unregist osgi service;
            info.unregisterOsgiServices();
            break;

          default:
            break;
          }
      }
  }

  @Override
  public void removedBundle(Bundle bundle, BundleEvent event,
                            OsgiServiceEntry info)
  {
    if (event != null)
      {
        MediatorActivator.printDebug("[PROVIDER_TRACKER] removedBundle[" +
                                     bundle.getBundleId() + "] " +
                                     ServiceLoaderProviderTracker
                                         .getState(event.getType()));
      }
    if (info != null)
      {
        info.unregisterOsgiServices();
      }
    activator_.unregisterProviderBundle(bundle);
  }

  /**
   * Parses and validates a single Service Provider declaration.
   *
   * The method removes an optional comment introduced by {@code '#'},
   * normalizes the remaining text, and validates that it represents a
   * syntactically valid Java binary class name.</p>
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
        MediatorActivator.logger_
            .error("Illegal configuration-file syntax: " + line);
        return null;
      }
    if (!isValidProviderClassName(line))
      {
        MediatorActivator.logger_.error("Illegal provider-class name: " + line);
        return null;
      }
    return line;
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
