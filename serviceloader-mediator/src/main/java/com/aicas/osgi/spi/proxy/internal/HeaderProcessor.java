/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.resource.Namespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

import com.aicas.osgi.spi.proxy.internal.ServiceLoaderProviderTracker.ProviderCapability;
import com.aicas.osgi.spi.proxy.internal.ServiceLoaderProviderTracker.RegisterMode;

public class HeaderProcessor
{
  static final Map<String, Object> CONSUMER_EXTENDER_PROCESSOR =
    createConsumerExtenderProcessorAttributes();

  static final Map<String, Object> PROVIDER_EXTENDER_REGISTRAR =
    createProviderExtenderRegistrarAttributes();

  private static Map<String, Object> createConsumerExtenderProcessorAttributes()
  {
    Map<String, Object> attributes = new java.util.HashMap<>();
    attributes.put(MediatorConstants.EXTENDER_CAPABILITY_NAMESPACE,
                   MediatorConstants.PROCESSOR_EXTENDER_NAME);
    attributes.put(MediatorConstants.VERSION_ATTRIBUTE,
                   MediatorConstants.SPECIFICATION_VERSION);
    return Collections.unmodifiableMap(attributes);
  }

  private static Map<String, Object> createProviderExtenderRegistrarAttributes()
  {
    Map<String, Object> attributes = new java.util.HashMap<>();
    attributes.put(MediatorConstants.EXTENDER_CAPABILITY_NAMESPACE,
                   MediatorConstants.REGISTRAR_EXTENDER_NAME);
    attributes.put(MediatorConstants.VERSION_ATTRIBUTE,
                   MediatorConstants.SPECIFICATION_VERSION);
    return Collections.unmodifiableMap(attributes);
  }

  private static final Pattern SERVICE_NAME_PATTERN =
      Pattern.compile("\\(" +
          Pattern.quote(MediatorConstants.SERVICELOADER_CAPABILITY_NAMESPACE) +
          "\\s*=\\s*" +
          "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)" +
          "\\s*\\)");

  private static final Pattern PROCESSOR_REQUIREMENT_PATTERN =
      Pattern.compile("\\(" +
          Pattern.quote(MediatorConstants.EXTENDER_CAPABILITY_NAMESPACE) +
          "\\s*=\\s*" +
          Pattern.quote(MediatorConstants.PROCESSOR_EXTENDER_NAME) +
          "\\s*\\)");

  /**
   * The outcome of inspecting a Consumer's processor-extender requirement.
   */
  static final class ConsumerRequirementResult
  {
    private final boolean processorRequirementDeclared_;
    private final boolean selectedByThisMediator_;
    private final Set<String> serviceTypes_;

    private ConsumerRequirementResult(boolean processorRequirementDeclared,
                                      boolean selectedByThisMediator,
                                      Set<String> serviceTypes)
    {
      processorRequirementDeclared_ = processorRequirementDeclared;
      selectedByThisMediator_ = selectedByThisMediator;
      serviceTypes_ = Set.copyOf(serviceTypes);
    }

    /** Returns whether a processor-extender requirement was declared. */
    public boolean hasProcessorRequirement()
    {
      return processorRequirementDeclared_;
    }

    /** Returns whether the processor requirement selected this mediator. */
    public boolean isSelectedByThisMediator()
    {
      return selectedByThisMediator_;
    }

    /**
     * Returns the declared Service Types when this mediator was selected, or
     * an empty set otherwise.
     */
    public Set<String> getServiceTypes()
    {
      return serviceTypes_;
    }
  }

  /**
   * Processes the ServiceLoader consumer requirements declared by the given
   * bundle and its attached fragments for the specified mediator bundle.
   *
   * <p>The host bundle revision and all attached fragment revisions are treated
   * as one consumer unit. If any of these revisions declares an
   * {@code osgi.extender} requirement for
   * {@code osgi.serviceloader.processor} and selects {@code mediatorBundle},
   * this method collects the service-type names extracted from all
   * {@code osgi.serviceloader} requirements declared by those revisions.</p>
   *
   * <p>This method does not inspect consumer class bytecode, determine whether
   * a class invokes {@link java.util.ServiceLoader#load(Class)}, or resolve
   * {@code osgi.serviceloader} requirements to concrete provider bundles. It
   * does inspect resolved extender wires to determine whether
   * {@code mediatorBundle} is the processor selected for the bundle.</p>
   *
   * @param bundle the host bundle whose consumer requirements should be inspected
   * @param mediatorBundle the mediator bundle that must be selected by the
   *        resolved processor extender wire
   * @return a result that distinguishes a missing processor requirement from a
   *         requirement selected by another mediator; service-type names are
   *         included only when {@code mediatorBundle} is selected
   */
  static ConsumerRequirementResult processConsumerRequirements(Bundle bundle,
                                                               Bundle mediatorBundle)
  {
    List<BundleRevision> revisions = getHostAndFragmentRevisions(bundle);
    boolean processorRequirementDeclared = false;
    for (BundleRevision revision : revisions)
      {
        List<BundleRequirement> requirements = revision.getDeclaredRequirements(
            MediatorConstants.EXTENDER_CAPABILITY_NAMESPACE);
        for (BundleRequirement requirement : requirements)
          {
            String filter = requirement.getDirectives().get(
                MediatorConstants.FILTER_DIRECTIVE);
            if (filter != null && PROCESSOR_REQUIREMENT_PATTERN.matcher(filter).find())
              {
                processorRequirementDeclared = true;
                break;
              }
          }
        if (processorRequirementDeclared)
          {
            break;
          }
      }

    if (!processorRequirementDeclared)
      {
        return new ConsumerRequirementResult(false,false, Set.of());
      }

    // The Service Loader Mediator specification requires a Consumer to select
    // exactly one processor mediator. Reject a processor wire declared with
    // cardinality:=multiple to avoid processing the same Consumer through
    // multiple processor extenders.
    if (!hasMediatorExtenderWire(revisions, CONSUMER_EXTENDER_PROCESSOR,
                                 mediatorBundle, true))
      {
        return new ConsumerRequirementResult(true, false, Set.of());
      }

    MediatorActivator.printDebug("[CONSUMER_PROCESSOR]Found osgi.serviceloader consumer - " +
                        bundle.getSymbolicName());
    Set<String> requiredServiceNames = new LinkedHashSet<>();
    for (BundleRevision revision : revisions)
      {
        List<BundleRequirement> requirements =
            revision.getDeclaredRequirements(MediatorConstants.SERVICELOADER_CAPABILITY_NAMESPACE);
        for (BundleRequirement req : requirements)
          {
            Set<String> serviceNames = extractServiceNames(req);
            requiredServiceNames.addAll(serviceNames);
            for (String serviceName : serviceNames)
              {
                MediatorActivator.printDebug(
                    "[CONSUMER_PROCESSOR] Found required service type"
                        + " - bundleID("
                        + revision.getBundle().getBundleId()
                        + ")"
                        + " - serviceType("
                        + serviceName
                        + ")");
              }
          }
      }
    return new ConsumerRequirementResult(true, true, requiredServiceNames);
  }
  private static Set<String> extractServiceNames(BundleRequirement requirement)
  {
      String filterExpression =
          requirement.getDirectives().get(MediatorConstants.FILTER_DIRECTIVE);

      if (filterExpression == null || filterExpression.isBlank())
        {
          return Set.of();
        }
      // Validate the complete LDAP filter before extracting service-type
      // clauses. A regex alone could accept a valid-looking fragment from an
      // otherwise malformed filter.
      try
        {
          FrameworkUtil.createFilter(filterExpression);
        }
      catch (InvalidSyntaxException e)
        {
          return Set.of();
        }
      Set<String> serviceNames = new LinkedHashSet<>();
      Matcher matcher = SERVICE_NAME_PATTERN.matcher(filterExpression);
      while (matcher.find())
        {
          serviceNames.add(matcher.group(1));
        }
      return serviceNames;
  }

  /**
   * Reads Service Loader Provider metadata declared by the given bundle
   * revisions.
   *
   * <p>The method collects the Service Types and service properties declared by
   * {@code osgi.serviceloader} capabilities and extracts the {@code register}
   * directive from each capability.</p>
   *
   * @param revisions the current host revision and its attached fragment
   *                  revisions.
   *
   * @param providerCapabilities the list to which capability metadata is added.
   * @return the declared Service Types, or {@code null} if none are declared.
   */
  static Set<String> readServiceLoaderProviderMetadata(List<BundleRevision> revisions,
                                                       List<ProviderCapability> providerCapabilities)
  {
    Set<String> serviceTypes = new HashSet<String>();
    for (BundleRevision revision : revisions)
      {
        List<BundleCapability> capabilities = revision.
            getDeclaredCapabilities(MediatorConstants.SERVICELOADER_CAPABILITY_NAMESPACE);
        for (BundleCapability cap : capabilities)
          {
            Object serviceTypeValue = cap.getAttributes().get(MediatorConstants.SERVICELOADER_CAPABILITY_NAMESPACE);

            // the service type.
            String serviceType = normalize((String)serviceTypeValue);
            if (serviceType == null)
              {
                MediatorActivator.logger_.error("Invalid osgi.serviceloader capability " + cap);
                continue;
              }
            serviceTypes.add(serviceType);
            MediatorActivator.printDebug("[PROVIDER_PROCESSOR] Found service type: " + serviceType);

            // parse attributes and register directive
            Hashtable<String, Object> attributes = new Hashtable<String, Object>();
            for (Entry<String, Object> entry : cap.getAttributes().entrySet())
              {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (key.equals(MediatorConstants.SERVICELOADER_CAPABILITY_NAMESPACE) ||
                    key.startsWith(".") ||
                    value == null)
                  {
                    continue;
                  }
                attributes.put(entry.getKey(), entry.getValue());
                MediatorActivator.printDebug("[PROVIDER_PROCESSOR] Provider Metadata " +
                             entry.getKey() + ") - (" + entry.getValue() + ")");
              }

            String selectedProvider = null;
            RegisterMode mode;
            String register = cap.getDirectives().get(MediatorConstants.REGISTER_DIRECTIVE);
            if (register == null)
              {
                mode = RegisterMode.ALL;
              }
            else
              {
                selectedProvider = normalize(register);

                if (selectedProvider == null)
                  {
                    mode = RegisterMode.NONE;
                  }
                else
                  {
                    mode = RegisterMode.SINGLE;
                  }
              }
            ProviderCapability pc = new ProviderCapability(serviceType,
                                                            selectedProvider,
                                                            mode,
                                                            attributes);
            providerCapabilities.add(pc);
            MediatorActivator.printDebug("add providerCapabilities: " + pc);
          }
      }
    if (serviceTypes.isEmpty())
      {
        return null;
      }
    return serviceTypes;
  }
  /**
  * Removes leading and trailing whitespace from the specified string.
  *
  * <p>If the input is {@code null}, empty, or contains only whitespace,
  * {@code null} is returned. Otherwise, the trimmed string is returned.</p>
  *
  * @param str the string to trim, or {@code null}.
  *
  * @return the trimmed string, or {@code null} if the input is
  *         {@code null}, empty, or contains only whitespace.
  */
  static String normalize(String str)
  {
    if (str == null)
      {
        return null;
      }
    str = str.trim();
    if ( str.length() == 0)
      {
        return null;
      }
    return str;
  }
  /**
   * Returns the current revision of the given bundle and the revisions of all
   * fragments currently attached to it.
   *
   * @param bundle the host bundle.
   *
   * @return the host revision followed by attached fragment revisions; the list
   *         is empty if the bundle is {@code null} or has no current revision.
   */
  static List<BundleRevision> getHostAndFragmentRevisions(Bundle bundle)
  {
    List<BundleRevision> revisions = new ArrayList<>();
    if (bundle != null)
      {
        BundleRevision hostRevision = bundle.adapt(BundleRevision.class);
        if (hostRevision != null)
          {
            revisions.add(hostRevision);

            BundleWiring wiring = hostRevision.getWiring();
            if (wiring != null)
              {
                for (BundleWire wire : wiring.getProvidedWires(HostNamespace.HOST_NAMESPACE))
                  {
                    BundleRevision fragmentRevision = wire.getRequirement().getRevision();

                    if (fragmentRevision != null)
                      {
                        revisions.add(fragmentRevision);
                      }
                  }
              }
            return revisions;
          }
      }
    return revisions;
  }

  /**
   * Returns whether an extender requirement of one of the supplied revisions
   * is actually wired to the supplied mediator bundle.
   *
   * @param revisions the host and attached fragment revisions to inspect.
   *
   * @param extenderAttributes the expected extender capability attributes.
   * @param mediatorBundle the bundle that supplies this mediator's extender
   *        capability.
   * @param requireSingleCardinality whether a matching requirement declared
   *        with {@code cardinality:=multiple} must be rejected. This is
   *        {@code true} for the Consumer processor extender, which must have
   *        single cardinality, and {@code false} for the provider registrar
   *        call, where this helper does not impose that restriction.
   *
   * @return {@code true} if a matching extender requirement is found;
   *         {@code false} otherwise.
   *
   */
  static boolean hasMediatorExtenderWire(List<BundleRevision> revisions,
                                         Map<String, Object> extenderAttributes,
                                         Bundle mediatorBundle,
                                         boolean requireSingleCardinality)
  {
    if (mediatorBundle == null)
      {
        return false;
      }
    for (BundleRevision revision : revisions)
      {
        BundleWiring wiring = revision.getWiring();
        if (wiring == null)
          {
            continue;
          }
        for (BundleWire wire : wiring.getRequiredWires(MediatorConstants.EXTENDER_CAPABILITY_NAMESPACE))
          {
            BundleRequirement requirement = wire.getRequirement();
            if (requireSingleCardinality &&
                Namespace.CARDINALITY_MULTIPLE.equals(requirement.getDirectives().
                                                      get(Namespace.REQUIREMENT_CARDINALITY_DIRECTIVE)))
              {
                continue;
              }
            BundleCapability capability = wire.getCapability();
            if (mediatorBundle.equals(wire.getProviderWiring().getBundle()) &&
                hasExpectedAttributes(capability, extenderAttributes))
              {
                MediatorActivator.printDebug("[HEADER_PROCESSOR]Found mediator extender wire - " +
                                            revision.getBundle().getSymbolicName());
                return true;
              }
          }
      }
    return false;
  }

  private static boolean hasExpectedAttributes(BundleCapability capability,
                                               Map<String, Object> expectedAttributes)
  {
    if (capability == null)
      {
        return false;
      }
    Map<String, Object> actualAttributes = capability.getAttributes();
    for (Entry<String, Object> expected : expectedAttributes.entrySet())
      {
        if (!expected.getValue().equals(actualAttributes.get(expected.getKey())))
          {
            return false;
          }
      }
    return true;
  }
}
