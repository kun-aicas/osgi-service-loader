/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.osgi.framework.Bundle;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

import com.aicas.osgi.spi.weaver.ServiceLoaderWeaver;

public class serviceLoaderWeavingHook implements WeavingHook
{
  private final MediatorActivator activator_;
  private static final String MEDIATOR_PACKAGE =
      com.aicas.osgi.spi.proxy.ServiceLoader.class.getPackage().getName();

  serviceLoaderWeavingHook(MediatorActivator act)
  {
    activator_ = act;
  }

  @Override
  public void weave(WovenClass wovenClass)
  {
    Bundle consumerBundle = wovenClass.getBundleWiring().getBundle();

    if ( !activator_.requiresProcessing(consumerBundle))
      {
        return;
      }

    byte[] originalBytes = wovenClass.getBytes();
    ServiceLoaderWeaver.WeavingResult result =
        ServiceLoaderWeaver.weave(originalBytes);

    if (!result.isSuccess())
      {
        MediatorActivator.logger_.debug(
            "ServiceLoader weaving did not transform " +
            wovenClass.getClassName());
        return;
      }
    byte[] wovenBytes = result.getBytes();
    MediatorActivator.printDebug("[WEAVING_HOOK] weaving [" +
                                 originalBytes.length + "]" +
                                 wovenClass.getClassName());

    //printClassBytecode(wovenClass);
    // Add the package visibility before publishing the transformed bytes.
    List<String> dynamicImports = wovenClass.getDynamicImports();

    if (!dynamicImports.contains(MEDIATOR_PACKAGE))
      {
        dynamicImports.add(MEDIATOR_PACKAGE);
      }

    wovenClass.setBytes(wovenBytes);

    MediatorActivator.printDebug("[PROCESSOR] Wove ServiceLoader references in " +
                                  wovenClass.getClassName());

    MediatorActivator.printDebug("[WEAVING_HOOK] finished weaving [" +
                         wovenClass.getBytes().length + "]" +
                         wovenClass.getClassName());
    //printClassBytecode(wovenClass);
  }

  /**
   * debug method. print the bytecode of the wovenClass.
   */
  private void printClassBytecode(WovenClass wovenClass)
  {
    byte[] bytes = wovenClass.getBytes();

    Textifier textifier = new Textifier();

    /*
     * The PrintWriter is intentionally null. The Textifier collects the
     * visited class structure, and we print it explicitly afterwards.
     */
    TraceClassVisitor tracer =
        new TraceClassVisitor(null, textifier, null);

    ClassReader reader = new ClassReader(bytes);
    reader.accept(tracer, 0);

    StringWriter output = new StringWriter();
    PrintWriter writer = new PrintWriter(output);

    textifier.print(writer);
    writer.flush();

    MediatorActivator.printDebug(
        "[WEAVING_HOOK] Woven class bytecode for "
            + wovenClass.getClassName()
            + " -------\n"
            + output
            + "-------------");
  }
}
