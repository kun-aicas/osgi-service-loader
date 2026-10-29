/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.weaver;

import static com.aicas.osgi.spi.weaver.WeaverConstants.JAVA_SERVICE_LOADER;
import static com.aicas.osgi.spi.weaver.WeaverConstants.OSGI_SERVICE_LOADER;

import java.util.Objects;
import java.util.logging.Level;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Remapper;

/**
 * Transactionally rewrites supported {@code java.util.ServiceLoader}
 * references for the OSGi Service Loader proxy.
 *
 * <p>Calls to {@code load(Class)} and {@code load(Class, ClassLoader)} are
 * redirected to private static {@code serviceLoaderBridge$load} methods
 * generated on the woven class. Each bridge preserves the original call
 * signature, then forwards to the corresponding proxy overload with the
 * woven class literal as its caller argument. Directly supported instance
 * methods continue to be remapped to the proxy type.</p>
 *
 * <p>Method handles targeting either static {@code load} overload in LDC
 * constants, {@code invokedynamic} bootstrap arguments, and constant-dynamic
 * values are redirected to the same bridges, preserving method-reference
 * signatures. Other supported ServiceLoader handles are remapped to the proxy
 * type. A bridge name receives a numeric suffix when the woven class already
 * declares that name and descriptor.</p>
 *
 * <p>Existing constant-pool indices and method bytecode are preserved. Type
 * text is replaced at its original index and static load references are
 * retargeted to bridges. Only bridge constants and methods are appended.</p>
 *
 * <p>Before returning transformed bytes, the weaver applies these checks:</p>
 * <ul>
 * <li>At least one supported ServiceLoader invocation must exist; otherwise
 *     the original bytes are returned without transformation.</li>
 * <li>ServiceLoader calls must use a supported opcode, name, and descriptor.
 *     This also applies to method handles in LDC constants, invokedynamic
 *     bootstrap methods and arguments, and recursively nested constant-dynamic
 *     values.</li>
 * <li>A UTF-8 slot requiring type replacement must not also hold protected
 *     literal or metadata text, such as annotation strings or source filenames.
 *     Multiple uses of one slot must not require conflicting replacements.</li>
 * <li>Attributes must be recognized so their UTF-8 references can be
 *     classified safely; unknown attributes prevent transformation.</li>
 * <li>Generated bridge names and descriptors must not collide with existing
 *     methods after type remapping. Numeric suffixes resolve collisions.</li>
 * <li>Class-file parsing and output checks must succeed, including referenced
 *     UTF-8 indices, parsed structure lengths and tags, and constant-pool and
 *     UTF-8 size limits. These checks are not a complete bytecode verifier.</li>
 * </ul>
 *
 * <p>Unsupported invocations, unsafe constant-pool replacements, and parsing
 * or transformation failures leave the entire original class unchanged.
 * The supplied byte array is never modified.</p>
 */
public final class ServiceLoaderWeaver
{
  /*----------------------- classes and enums -------------------------*/
  /**
   * Remaps JDK ServiceLoader type references represented in the class file.
   */
  private static final class ServiceLoaderTypeRemapper
    extends Remapper
  {
    @Override
    public String map(String internalName)
    {
      if (JAVA_SERVICE_LOADER.equals(internalName))
        {
          return OSGI_SERVICE_LOADER;
        }

      return internalName;
    }
  }

  /*--------------------------- constants -----------------------------*/

  private static final java.util.logging.Logger LOGGER =
    java.util.logging.Logger.getLogger(ServiceLoaderWeaver.class.getName());

  private static final ServiceLoaderTypeRemapper
    SERVICE_LOADER_TYPE_REMAPPER = new ServiceLoaderTypeRemapper();


  /*--------------------------- variables -----------------------------*/

  /*------------------------ native functions -------------------------*/

  /*------------------------  constructors  ---------------------------*/

  private ServiceLoaderWeaver()
  {
  }

  /*---------------------------- methods ------------------------------*/

  /**
   * Attempts to weave one class and generate any required load bridges.
   *
   * <p>The supplied byte array is never modified. When weaving cannot be
   * completed, the result contains the original byte array and reports the
   * reason through its {@linkplain WeavingResult#getStatus() status}.</p>
   *
   * <p>Successful weaving remaps ServiceLoader type references, redirects the
   * two static {@code load} overloads and their method handles to bridge
   * targets on the woven class, and remaps the remaining supported calls and
   * handles to the proxy type.</p>
   *
   * @param originalBytes original class-file bytes
   *
   * @return the weaving result
   *
   * @throws NullPointerException if {@code originalBytes} is {@code null}
   */
  public static WeavingResult weave(byte[] originalBytes)
  {
    Objects.requireNonNull(originalBytes, "originalBytes");

    try
      {
        ClassReader reader = new ClassReader(originalBytes);
        // Analyze original invocations before changing any constant-pool content.
        ServiceLoaderClassVisitor visitor =
          new ServiceLoaderClassVisitor(SERVICE_LOADER_TYPE_REMAPPER);
        reader.accept(visitor, 0);
        if (!visitor.hasSupportedInvocation())
          {
            return WeavingResult.noSupportedInvocation(originalBytes);
          }

        ConstantPoolRewriter cpoolRewriter =
            new ConstantPoolRewriter(originalBytes, SERVICE_LOADER_TYPE_REMAPPER);

        byte[] wovenBytes = cpoolRewriter.rewriteConstantPool(visitor::getLoadBridgeName);

        ClassReader rewrittenReader = new ClassReader(wovenBytes);

        // Seed from the rewritten pool and return MethodWriters directly so ASM
        // copies existing methods verbatim, including their stack-map frames.
        ClassWriter writer = new ClassWriter(rewrittenReader,
                                             ClassWriter.COMPUTE_MAXS);

        rewrittenReader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
          @Override
          public void visitEnd()
          {
            visitor.addLoadBridges(cv);
            super.visitEnd();
          }
        }, 0);

        return WeavingResult.woven(writer.toByteArray());
      }
    catch (ConstantPoolRewriter.UnsafeConstantPoolException exception)
      {
        return new WeavingResult(WeavingResult.Status.UNSAFE_CONSTANT_POOL,
                                 originalBytes);
      }
    catch (ServiceLoaderMethodVisitor.UnsupportedInvocationException exception)
      {
        /*
         * Expected rejection: the class invokes an unsupported ServiceLoader
         * method.
         */
        return WeavingResult.unsupportedInvocation(originalBytes);
      }
    catch (RuntimeException exception)
      {
        /*
         * The weaver is also used directly by unit tests and can be called
         * before the OSGi activator has initialized its logger.
         */
        LOGGER.log(Level.FINE, "Failed to weave class", exception);
        /*
         * Malformed bytecode, ASM failure, or another transformation error.
         * The original class remains usable.
         */
        return WeavingResult.transformationError(originalBytes, exception);
      }
  }
}
