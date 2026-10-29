/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingException;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;
import org.osgi.framework.wiring.BundleWiring;

import com.aicas.osgi.spi.weaver.ServiceLoaderWeaver;
import com.aicas.osgi.spi.weaver.WeavingResult;
import com.aicas.osgi.spi.weaver.StaticWeavingStatus;
import com.aicas.osgi.spi.weaver.WeaverConstants;

public class ServiceLoaderWeavingHook implements WeavingHook
{
  /** Cached weaving decision for a bundle wiring. */
  private enum WiringAnalysis
  {
    /** Do not weave classes from this wiring. */
    SKIP,

    /** Weave only if the bundle's metadata selects this mediator. */
    PROCESSOR_REQUIRED,

    /** Allow weaving without processor metadata when framework-level support is enabled. */
    METADATA_FREE
  }

  private final MediatorActivator activator_;
  private final Bundle weaverBundle_;

  /**
   * Cached weaving decisions, held by strong references for this hook's lifetime.
   * Eligible wirings still require permission and consumer-registration checks
   * on each load. Analysis runs outside map callbacks to permit class loading
   * to re-enter the hook safely.
   */
  private final ConcurrentMap<BundleWiring, WiringAnalysis> wiringAnalyses_ =
      new ConcurrentHashMap<>();

  ServiceLoaderWeavingHook(MediatorActivator act)
  {
    activator_ = act;
    // Resolve this before registering the hook. Resolving the weaver bundle
    // from weave() can otherwise trigger a class-loading cycle.
    weaverBundle_ = FrameworkUtil.getBundle(ServiceLoaderWeaver.class);
    // Loading the analysis enum from weave() would also re-enter this hook.
    WiringAnalysis.values();
    // These entries are available before any class enters the registered hook.
    wiringAnalyses_.putIfAbsent(activator_.getBundle(0).adapt(BundleWiring.class),
                                WiringAnalysis.SKIP);
    wiringAnalyses_.putIfAbsent(activator_.getMediatorBundle().adapt(BundleWiring.class),
                                WiringAnalysis.SKIP);
    wiringAnalyses_.putIfAbsent(weaverBundle_.adapt(BundleWiring.class),
                                WiringAnalysis.SKIP);
  }


  @Override
  public void weave(WovenClass wovenClass)
  {
    if (!shouldWeave(wovenClass))
      {
        return;
      }

    byte[] originalBytes = wovenClass.getBytes();
    WeavingResult result = ServiceLoaderWeaver.weave(originalBytes);

    if (result.getStatus() != WeavingResult.Status.WOVEN)
      {
        MediatorActivator.logger_.debug("ServiceLoader weaving did not transform " +
                                         wovenClass.getClassName() + ": " +
                                         result.getStatus());
        return;
      }
    byte[] wovenBytes = result.getBytes();

    List<String> dynamicImports = wovenClass.getDynamicImports();

    if (!dynamicImports.contains(WeaverConstants.PROXY_IMPORT))
      {
        dynamicImports.add(WeaverConstants.PROXY_IMPORT);
      }
    wovenClass.setBytes(wovenBytes);

    printClassBytecode(originalBytes);
    printClassBytecode(wovenBytes);
  }

  /**
   * Determines whether the mediator should attempt to weave this class.
   *
   * <ul>
   *   <li>Statically processed wirings are excluded.</li>
   *   <li>Infrastructure bundles are excluded.</li>
   *   <li>Bundles whose metadata the mediator cannot currently read are excluded.</li>
   *   <li>A bundle that selects another mediator is excluded.</li>
   *   <li>An otherwise eligible consumer selected by this mediator is processed.</li>
   *   <li>A metadata-free consumer is processed only when framework-level
   *       ServiceLoader support is enabled.</li>
   * </ul>
   *
   * <p>Metadata classification is cached per wiring. Proxy import versions are
   * not inspected: deployment must provide the matching mediator as the only
   * proxy package exporter, and the mediator requires the exact weaver API
   * version. Each transformed class receives the generated proxy import.</p>
   *
   * <p>Cached exclusions return immediately. Metadata permissions and consumer
   * registration status are checked on each eligible call because they can
   * change without a new wiring. Permission denial and invalid static
   * processing headers are not cached.</p>
   *
   * @param wovenClass the class being considered by the weaving hook.
   * @return {@code true} when the mediator should attempt to weave the class.
   * @throws WeavingException if analysis encounters an invalid static processing
   *         header value.
   */
  private boolean shouldWeave(WovenClass wovenClass)
  {
    BundleWiring wiring = wovenClass.getBundleWiring();
    WiringAnalysis analysis = wiringAnalyses_.get(wiring);
    if (analysis == WiringAnalysis.SKIP)
      {
        return false;
      }

    Bundle consumerBundle = wiring.getBundle();
    if (consumerBundle == null)
      {
        return false;
      }

    if (analysis == null &&
        (consumerBundle.getBundleId() == 0 ||
         consumerBundle.equals(activator_.getMediatorBundle()) ||
         consumerBundle.equals(weaverBundle_) ||
         WeaverConstants.isInfrastructureBundle(consumerBundle.getSymbolicName())))
      {
        // Identity checks cover new wirings created after bundle refresh.
        // Exclude the weaver before calling its infrastructure helper.
        wiringAnalyses_.putIfAbsent(wiring, WiringAnalysis.SKIP);
        return false;
      }

    if (!MediatorSecurity.canReadMetadata(activator_.getMediatorBundle(),
                                          consumerBundle))
    {// not cache the analysis.
      return false;
    }

    if (analysis == null)
      {
        // Metadata access can load another class and re-enter this hook.
        // Publish only the completed decision, outside any map callback.
        analysis = isStaticallyProcessed(consumerBundle) ? WiringAnalysis.SKIP :
            HeaderProcessor.hasProcessorExtenderRequirement(consumerBundle) ?
                                          WiringAnalysis.PROCESSOR_REQUIRED :
                                          WiringAnalysis.METADATA_FREE;
        wiringAnalyses_.putIfAbsent(wiring, analysis);
      }

    if (analysis == WiringAnalysis.SKIP)
      {
        return false;
      }

    return activator_.isMetadataConsumer(consumerBundle) ||
           (analysis == WiringAnalysis.METADATA_FREE &&
            activator_.isFrameworkServiceLoaderEnabled());
  }

  /**
   * Checks the bundle's static processing header.
   *
   * @param bundle bundle whose headers are checked.
   * @return {@code true} if the status is valid; {@code false} if absent.
   * @throws WeavingException if the header is present with an invalid value.
   */
  private boolean isStaticallyProcessed(Bundle bundle)
  {
    String value = bundle.getHeaders().get(WeaverConstants.STATIC_WEAVING_HEADER);
    if (value == null)
      {
        return false;
      }
    if (!StaticWeavingStatus.isValid(value))
      {
        throw new WeavingException("Invalid " +
                                   WeaverConstants.STATIC_WEAVING_HEADER +
                                   " value '" + value + "' in bundle " +
                                   bundle.getSymbolicName() +
                                   " [" + bundle.getBundleId() +
                                   "].");
      }
    MediatorActivator.logger_.debug("Skipping ServiceLoader weaving for woven bundle " +
        bundle.getSymbolicName() + " [" + bundle.getBundleId() + "]");
    return true;
  }

  /**
   * Renders a snapshot of class bytes for internal debugging.
   */
  private void printClassBytecode(byte[] bytes)
  {

    Textifier textifier = new Textifier();

    TraceClassVisitor tracer =
        new TraceClassVisitor(null, textifier, null);

    ClassReader reader = new ClassReader(bytes);
    reader.accept(tracer, 0);

    StringWriter output = new StringWriter();
    PrintWriter writer = new PrintWriter(output);

    textifier.print(writer);
    writer.flush();

    MediatorActivator.printDebug("[WEAVING_HOOK] Woven class bytecode for " +
                                 " -------\n" + output + "-------------");
  }

}
