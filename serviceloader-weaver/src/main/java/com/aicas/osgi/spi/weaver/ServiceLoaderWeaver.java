/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.weaver;

import static org.objectweb.asm.Opcodes.H_INVOKESTATIC;
import static org.objectweb.asm.Opcodes.H_INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;

import java.io.Serial;
import java.util.Collections;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.Objects;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Transactionally replaces supported references to
 * {@code java.util.ServiceLoader} with references to
 * {@code com.aicas.osgi.spi.proxy.ServiceLoader}.
 *
 * <p>The class is processed in a single ASM traversal. During that traversal,
 * supported ServiceLoader invocations are validated and all ServiceLoader type
 * references are remapped.</p>
 *
 * <p>If an unsupported ServiceLoader invocation is encountered, processing
 * stops immediately and the original class bytes are returned unchanged.</p>
 *
 * <p>If the class does not contain any supported ServiceLoader invocation, no
 * transformed byte array is generated and the original bytes are returned.</p>
 */
public final class ServiceLoaderWeaver
{
  private static final java.util.logging.Logger LOGGER =
      java.util.logging.Logger.getLogger(ServiceLoaderWeaver.class.getName());

  static final String JAVA_SERVICE_LOADER = "java/util/ServiceLoader";

  static final String JAVA_SERVICE_LOADER_DESCRIPTOR =
      "L" + JAVA_SERVICE_LOADER + ";";

  static final String OSGI_SERVICE_LOADER = "com/aicas/osgi/spi/proxy/ServiceLoader";

  static final String OSGI_SERVICE_LOADER_DESCRIPTOR = "L" + OSGI_SERVICE_LOADER + ";";

  /**
   * Supported methods are identified using the original JDK owner,
   * invocation opcode, method name, and descriptor.
   *
   * <p>To add support for another ServiceLoader method, add its exact method
   * key to this set and provide the corresponding method in
   * {@code com.aicas.osgi.spi.proxy.ServiceLoader}.</p>
   */
  private static final Set<MethodKey> SUPPORTED_METHODS;
  static
    {
      Set<MethodKey> methods = new HashSet<MethodKey>();

      methods.add(new MethodKey(INVOKESTATIC,
                                "load",
                                "(Ljava/lang/Class;)" + JAVA_SERVICE_LOADER_DESCRIPTOR));

      methods.add(new MethodKey(INVOKESTATIC,
                                "load",
                                "(Ljava/lang/Class;Ljava/lang/ClassLoader;)" +
                                        JAVA_SERVICE_LOADER_DESCRIPTOR));

      methods.add(new MethodKey(INVOKESTATIC,
                                "loadInstalled",
                                "(Ljava/lang/Class;)" +
                                        JAVA_SERVICE_LOADER_DESCRIPTOR));

      methods.add(new MethodKey(INVOKEVIRTUAL,
                                "iterator",
                                "()Ljava/util/Iterator;"));

      methods.add(new MethodKey(INVOKEVIRTUAL,
                                "reload",
                                "()V"));

      methods.add(new MethodKey(INVOKEVIRTUAL,
                                "findFirst",
                                "()Ljava/util/Optional;"));

      methods.add(new MethodKey(INVOKEVIRTUAL, "toString",
                                "()Ljava/lang/String;"));

      SUPPORTED_METHODS = Collections.unmodifiableSet(methods);
    }


  private ServiceLoaderWeaver()
  {
  }

  /**
   * Attempts to weave one class.
   *
   * <p>The supplied byte array is never modified. When weaving cannot be
   * completed, the result contains the original byte array and reports
   * {@code success == false}.</p>
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
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);

        ServiceLoaderClassVisitor visitor =
          new ServiceLoaderClassVisitor(writer);

        /*
         * Validation and transformation happen during this single traversal.
         */
        reader.accept(visitor, 0);

        /*
         * Do not call writer.toByteArray() when no supported invocation was
         * found.
         */
        if (!visitor.hasSupportedInvocation())
          {
            return WeavingResult.notWoven(originalBytes);
          }

        /*
         * This point is reached only when the complete class was processed
         * without encountering an unsupported invocation.
         */
        return WeavingResult.success(writer.toByteArray());
      }
    catch (UnsupportedInvocationException exception)
      {
        /*
         * Expected rejection: the class invokes an unsupported ServiceLoader
         * method.
         */
        return WeavingResult.notWoven(originalBytes);
      }
    catch (RuntimeException exception)
      {
        /*
         * The weaver is also used directly by unit tests and can be called
         * before the OSGi activator has initialized its logger. Logging must
         * not turn a recoverable weaving failure into a NullPointerException.
         */
        LOGGER.log(Level.FINE, "Failed to weave class", exception);
        /*
         * Malformed bytecode, ASM failure, or another transformation error.
         * The original class remains usable.
         */
        return WeavingResult.notWoven(originalBytes);
      }
  }

  /**
   * Remaps every occurrence of the JDK ServiceLoader internal type name.
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

  /**
   * Performs class-wide type remapping and installs validating method
   * visitors.
   */
  private static final class ServiceLoaderClassVisitor
    extends ClassRemapper
  {
    private boolean supportedInvocationFound;

    private ServiceLoaderClassVisitor(ClassVisitor delegate)
    {
      super(Opcodes.ASM9, delegate, new ServiceLoaderTypeRemapper());
    }

    @Override
    public MethodVisitor visitMethod(int access,
                                     String name,
                                     String descriptor,
                                     String signature,
                                     String[] exceptions)
    {
      /*
       * ClassRemapper returns a MethodRemapper here. Our visitor wraps that
       * MethodRemapper so that validation sees the original instruction before
       * the delegate remaps it.
       */
      MethodVisitor remappingVisitor = super.visitMethod(access,
                                                         name,
                                                         descriptor,
                                                         signature,
                                                         exceptions);

      if (remappingVisitor == null)
        {
          return null;
        }

      return new ServiceLoaderMethodVisitor(remappingVisitor,
                                            this);
    }

    private void markSupportedInvocation()
    {
      supportedInvocationFound = true;
    }

    private boolean hasSupportedInvocation()
    {
      return supportedInvocationFound;
    }
  }

  /**
   * Validates ServiceLoader invocations before forwarding them to the ASM
   * remapper.
   */
  private static final class ServiceLoaderMethodVisitor extends MethodVisitor
  {
    private final ServiceLoaderClassVisitor classVisitor;

    private ServiceLoaderMethodVisitor(
                                       MethodVisitor delegate,
                                       ServiceLoaderClassVisitor classVisitor)
    {
      super(Opcodes.ASM9, delegate);
      this.classVisitor = classVisitor;
    }

    @Override
    public void visitMethodInsn(int opcode,
                                String owner,
                                String name,
                                String descriptor,
                                boolean isInterface)
    {
      validateInvocation(opcode,
                         owner,
                         name,
                         descriptor);

      /*
       * The delegate is ASM's MethodRemapper. It remaps both the invocation
       * owner and ServiceLoader occurrences in the descriptor.
       */
      super.visitMethodInsn(opcode,
                            owner,
                            name,
                            descriptor,
                            isInterface);
    }

    @Override
    public void visitInvokeDynamicInsn(String name,
                                       String descriptor,
                                       Handle bootstrapMethodHandle,
                                       Object... bootstrapMethodArguments)
    {
      /*
       * Validate ServiceLoader method references used by lambdas or method
       * references, for example ServiceLoader::load.
       */
      validateConstant(bootstrapMethodHandle);

      for (Object argument : bootstrapMethodArguments)
        {
          validateConstant(argument);
        }

      super.visitInvokeDynamicInsn(
                                   name,
                                   descriptor,
                                   bootstrapMethodHandle,
                                   bootstrapMethodArguments);
    }

    @Override
    public void visitLdcInsn(Object value)
    {
      /*
       * A method handle or ConstantDynamic may also appear directly as an LDC
       * constant.
       */
      validateConstant(value);
      super.visitLdcInsn(value);
    }

    private void validateConstant(Object value)
    {
      if (value instanceof Handle)
        {
          validateHandle((Handle) value);
        }
      else if (value instanceof ConstantDynamic)
        {
          validateConstantDynamic((ConstantDynamic) value);
        }
    }

    private void validateConstantDynamic(ConstantDynamic constant)
    {
      validateHandle(constant.getBootstrapMethod());

      for (int index = 0; index < constant
          .getBootstrapMethodArgumentCount(); index++)
        {
          validateConstant(constant.getBootstrapMethodArgument(index));
        }
    }

    private void validateHandle(Handle handle)
    {
      if (!JAVA_SERVICE_LOADER.equals(handle.getOwner()))
        {
          return;
        }

      int opcode;

      switch (handle.getTag())
        {
        case H_INVOKESTATIC:
          opcode = INVOKESTATIC;
          break;

        case H_INVOKEVIRTUAL:
          opcode = INVOKEVIRTUAL;
          break;

        default:
          /*
           * INVOKEINTERFACE, INVOKESPECIAL, constructors, and field handles
           * are not supported for java.util.ServiceLoader.
           */
          throw UnsupportedInvocationException.INSTANCE;
        }

      validateInvocation(opcode,
                         handle.getOwner(),
                         handle.getName(),
                         handle.getDesc());
    }

    private void validateInvocation(int opcode,
                                    String owner,
                                    String name,
                                    String descriptor)
    {
      if (!JAVA_SERVICE_LOADER.equals(owner))
        {
          return;
        }

      /*
       * Only INVOKESTATIC and INVOKEVIRTUAL are valid supported forms.
       */
      if (opcode != INVOKESTATIC && opcode != INVOKEVIRTUAL)
        {
          throw UnsupportedInvocationException.INSTANCE;
        }

      MethodKey invocation = new MethodKey(opcode, name, descriptor);

      if (!SUPPORTED_METHODS.contains(invocation))
        {
          /*
           * Abort the ClassReader traversal immediately. Any partially written
           * data in ClassWriter is discarded.
           */
          throw UnsupportedInvocationException.INSTANCE;
        }

      classVisitor.markSupportedInvocation();
    }
  }

  /**
   * Identifies one supported ServiceLoader method invocation.
   */
  private static final class MethodKey
  {
    private final int opcode;
    private final String name;
    private final String descriptor;

    private MethodKey(int opcode,
                      String name,
                      String descriptor)
    {
      this.opcode = opcode;
      this.name = Objects.requireNonNull(name, "name");
      this.descriptor =
        Objects.requireNonNull(descriptor, "descriptor");
    }

    @Override
    public boolean equals(Object object)
    {
      if (this == object)
        {
          return true;
        }

      if (!(object instanceof MethodKey))
        {
          return false;
        }

      MethodKey other = (MethodKey) object;

      return opcode == other.opcode && name.equals(other.name) &&
             descriptor.equals(other.descriptor);
    }

    @Override
    public int hashCode()
    {
      int result = Integer.hashCode(opcode);
      result = 31 * result + name.hashCode();
      result = 31 * result + descriptor.hashCode();
      return result;
    }
  }

  /**
   * Stops the current ASM traversal when an unsupported invocation is found.
   *
   * <p>No stack trace is created because this exception represents an expected
   * validation result rather than an implementation error.</p>
   */
  private static final class UnsupportedInvocationException
    extends RuntimeException
  {
    @Serial
    private static final long serialVersionUID = 1L;

    private static final UnsupportedInvocationException INSTANCE =
      new UnsupportedInvocationException();

    private UnsupportedInvocationException()
    {
      super(null, null, false, false);
    }
  }

  /**
   * Result returned by {@link ServiceLoaderWeaver#weave(byte[])}.
   */
  public static final class WeavingResult
  {
    private final boolean success;
    private final byte[] bytes;

    private WeavingResult(boolean success,
                          byte[] bytes)
    {
      this.success = success;
      this.bytes = Objects.requireNonNull(bytes, "bytes");
    }

    private static WeavingResult success(byte[] wovenBytes)
    {
      return new WeavingResult(true,
                               wovenBytes);
    }

    private static WeavingResult notWoven(byte[] originalBytes)
    {
      return new WeavingResult(false,
                               originalBytes);
    }

    /**
     * Returns whether the complete class was successfully woven.
     *
     * @return {@code true} if woven bytes were produced; otherwise
     *         {@code false}
     */
    public boolean isSuccess()
    {
      return success;
    }

    /**
     * Returns the woven bytes when {@link #isSuccess()} is {@code true}, or
     * the original bytes otherwise.
     *
     * @return class-file bytes
     */
    public byte[] getBytes()
    {
      return bytes;
    }
  }
}
