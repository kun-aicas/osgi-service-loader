/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.weaver;

import static com.aicas.osgi.spi.weaver.WeaverConstants.JAVA_LOAD_DESCRIPTOR;
import static com.aicas.osgi.spi.weaver.WeaverConstants.JAVA_LOAD_WITH_LOADER_DESCRIPTOR;
import static com.aicas.osgi.spi.weaver.WeaverConstants.JAVA_SERVICE_LOADER;

import static org.objectweb.asm.Opcodes.H_INVOKESTATIC;
import static org.objectweb.asm.Opcodes.H_INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;

import java.io.Serial;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Validates original ServiceLoader calls and recursively inspects handles
 * in bootstrap arguments and dynamic constants.
 */
final class ServiceLoaderMethodVisitor extends MethodVisitor
{
  /*----------------------- classes and enums -------------------------*/
  /**
   * Identifies one supported ServiceLoader method invocation.
   */
  protected static final class MethodKey
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
  static final class UnsupportedInvocationException
          extends RuntimeException
  {
    @Serial
    private static final long serialVersionUID = 1L;

    static final UnsupportedInvocationException INSTANCE =
            new UnsupportedInvocationException();

    private UnsupportedInvocationException()
    {
      super(null, null, false, false);
    }
  }

  /*--------------------------- constants -----------------------------*/
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

    methods.add(new MethodKey(INVOKESTATIC, "load", JAVA_LOAD_DESCRIPTOR));

    methods.add(new MethodKey(INVOKESTATIC, "load", JAVA_LOAD_WITH_LOADER_DESCRIPTOR));

    methods.add(new MethodKey(INVOKEVIRTUAL, "iterator", "()Ljava/util/Iterator;"));

    methods.add(new MethodKey(INVOKEVIRTUAL, "reload", "()V"));

    methods.add(new MethodKey(INVOKEVIRTUAL, "findFirst", "()Ljava/util/Optional;"));

    methods.add(new MethodKey(INVOKEVIRTUAL, "toString",  "()Ljava/lang/String;"));

    SUPPORTED_METHODS = Collections.unmodifiableSet(methods);
  }

  /*--------------------------- variables -----------------------------*/
  private final ServiceLoaderClassVisitor classVisitor;

  /*------------------------  constructors  ---------------------------*/

  ServiceLoaderMethodVisitor(ServiceLoaderClassVisitor classVisitor)
  {
    super(Opcodes.ASM9);
    this.classVisitor = classVisitor;
  }

  /*---------------------------- methods ------------------------------*/
  @Override
  public void visitMethodInsn(int opcode, String owner, String name,
                              String descriptor, boolean isInterface)
  {
    validateInvocation(opcode, owner, name, descriptor);
  }

  @Override
  /**
   * Function<Class<Runnable>, ServiceLoader<Runnable>> loader =
   *     ServiceLoader::load;
   *
   * visitInvokeDynamicInsn
   * ("apply",                                  // Function's method name
   *   "()Ljava/util/function/Function;",        // creates a Function; captures nothing
   *   bootstrapMethodHandle,                    // LambdaMetafactory.metafactory
   *   // Bootstrap arguments:
   *   bootstrapMethodArguments[0] Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),
   *   bootstrapMethodArguments[1] new Handle(Opcodes.H_INVOKESTATIC,
   *                                          "java/util/ServiceLoader",
   *                                          "load",
   *                                          "(Ljava/lang/Class;)Ljava/util/ServiceLoader;",
   *                                           false),
   *   bootstrapMethodArguments[2] Type.getMethodType("(Ljava/lang/Class;)Ljava/util/ServiceLoader;")
   * );
   */
  public void visitInvokeDynamicInsn(String name, String descriptor,
                                     Handle bootstrapMethodHandle,
                                     Object... bootstrapMethodArguments)
  {
    validateHandle(bootstrapMethodHandle);
    for (Object value : bootstrapMethodArguments)
      {
        validateConstant(value);
      }
  }

  @Override
  public void visitLdcInsn(Object value)
  {
    validateConstant(value);
  }

  /**
   * Validates method handles in a constant, recursively checking dynamic
   * constants' bootstrap method handles and arguments.
   * Other constant types are ignored.
   *
   * @param value the constant or bootstrap argument to inspect.
   * @throws UnsupportedInvocationException if a method handle references
   *         an unsupported JDK ServiceLoader operation.
   */
  private void validateConstant(Object value)
  {
    if (value instanceof Handle handle)
      {
        validateHandle(handle);
      }
    else if (value instanceof ConstantDynamic constant)
      {
        validateHandle(constant.getBootstrapMethod());
        for (int index = 0; index < constant.getBootstrapMethodArgumentCount(); index++)
          {
            validateConstant(constant.getBootstrapMethodArgument(index));
          }
      }
  }

  /**
   *
   * @param handle the bootstrap method.
   */
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

  /**
   * Validates a JDK ServiceLoader invocation and marks the class as containing
   * a supported invocation. Invocations owned by other classes are ignored.
   *
   * @param opcode the opcode of the type instruction to be visited.
   * @param owner the internal name of the method's owner class.
   * @param name  method's name.
   * @param descriptor method's descriptor.
   * @throws UnsupportedInvocationException if a JDK ServiceLoader invocation
   *         uses an unsupported opcode or method signature.
   */
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
         * Abort analysis immediately; the original bytes remain untouched.
         */
        throw UnsupportedInvocationException.INSTANCE;
      }
    classVisitor.markSupportedInvocation();
  }
}
