/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.weaver;

import static com.aicas.osgi.spi.weaver.WeaverConstants.*;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;

/**
 * Analyzes original invocations and declared methods, then emits bridges
 * after the constant pool has been rewritten.
 */
final class ServiceLoaderClassVisitor extends ClassVisitor
{
  /*----------------------- classes and enums -------------------------*/

  /** Identifies a declared or generated method by its JVM name and descriptor. */
  private static final class MethodSignature
  {
    private final String name;
    private final String descriptor;

    private MethodSignature(String name, String descriptor)
    {
      this.name = name;
      this.descriptor = descriptor;
    }

    @Override
    public boolean equals(Object object)
    {
      if (!(object instanceof MethodSignature))
        {
          return false;
        }
      MethodSignature other = (MethodSignature) object;
      return name.equals(other.name) && descriptor.equals(other.descriptor);
    }

    @Override
    public int hashCode()
    {
      return 31 * name.hashCode() + descriptor.hashCode();
    }
  }

  /** The supported load forms and the descriptors used at each rewrite stage.*/
  private enum LoadBridge
  {
    LOAD(JAVA_LOAD_DESCRIPTOR,
         BRIDGE_LOAD_DESCRIPTOR,
         PROXY_LOAD_DESCRIPTOR,
         1),
    LOAD_WITH_LOADER(JAVA_LOAD_WITH_LOADER_DESCRIPTOR,
                     BRIDGE_LOAD_WITH_LOADER_DESCRIPTOR,
                     PROXY_LOAD_WITH_LOADER_DESCRIPTOR,
                     2);

    private final String javaDescriptor;
    private final String bridgeDescriptor;
    private final String proxyDescriptor;
    private final int argumentCount;

    LoadBridge(String javaDescriptor,
               String bridgeDescriptor,
               String proxyDescriptor,
               int argumentCount)
    {
      this.javaDescriptor = javaDescriptor;
      this.bridgeDescriptor = bridgeDescriptor;
      this.proxyDescriptor = proxyDescriptor;
      this.argumentCount = argumentCount;
    }

    /**
     * Returns the bridge matching an original JDK ServiceLoader load descriptor.
     *
     * @param descriptor the JVM method descriptor of the JDK load method
     * @return the matching bridge, or {@code null} if no bridge matches
     */
    private static LoadBridge getBridge(String descriptor)
    {
      for (LoadBridge bridge : values())
        {
          if (bridge.javaDescriptor.equals(descriptor))
            {
              return bridge;
            }
        }
      return null;
    }
  }

  /*--------------------------- variables -----------------------------*/

  private boolean supportedInvocationFound;
  private final Set<MethodSignature> declaredMethods = new HashSet<>();
  private final Map<LoadBridge, String> bridgeNames = new EnumMap<>(LoadBridge.class);
  private String wovenClassName;

  private final Remapper typeRemapper;

  /*------------------------  constructors  ---------------------------*/

  ServiceLoaderClassVisitor(Remapper typeRemapper)
  {
    super(Opcodes.ASM9);
    this.typeRemapper = typeRemapper;
  }

  /*---------------------------- methods ------------------------------*/
  @Override
  public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces)
  {
    wovenClassName = name;
  }

  @Override
  public MethodVisitor visitMethod(int access, String name, String descriptor,
                                   String signature, String[] exceptions)
  {
    declaredMethods.add(new MethodSignature(name,
                                            typeRemapper.mapMethodDesc(descriptor)));
    return new ServiceLoaderMethodVisitor(this);
  }

  void markSupportedInvocation()
  {
    supportedInvocationFound = true;
  }

  boolean hasSupportedInvocation()
  {
    return supportedInvocationFound;
  }

  /**
   * Returns the generated bridge for one load signature, allocating a name
   * that does not collide with a remapped declared method.
   */
  String getLoadBridgeName(String descriptor)
  {
    LoadBridge bridge = LoadBridge.getBridge(descriptor);
    String bridgeName = bridgeNames.get(bridge);
    if (bridgeName == null)
      {
        bridgeName = allocateBridgeName(bridge.bridgeDescriptor);
        bridgeNames.put(bridge, bridgeName);
      }
    return bridgeName;
  }

  /**
   * Allocates and reserves a bridge name for the supplied JVM method descriptor.
   * Starts with {@code BRIDGE_LOAD_NAME} and appends {@code $1}, {@code $2},
   * and so on until the name and descriptor do not collide with a remapped
   * declared method or a previously reserved bridge.
   *
   * @param descriptor the JVM method descriptor of the bridge.
   * @return the reserved bridge name.
   */
  private String allocateBridgeName(String descriptor)
  {
    String name = BRIDGE_LOAD_NAME;
    int suffix = 1;
    while (declaredMethods.contains(new MethodSignature(name, descriptor)))
      {
        name = BRIDGE_LOAD_NAME + "$" + suffix++;
      }
    declaredMethods.add(new MethodSignature(name, descriptor));
    return name;
  }

  void addLoadBridges(ClassVisitor writer)
  {
    for (LoadBridge bridge : LoadBridge.values())
      {
        String bridgeName = bridgeNames.get(bridge);
        if (bridgeName != null)
          {
            addLoadBridge(writer, bridgeName, bridge);
          }
      }
  }

  /**
   * Emits a private static synthetic bridge that appends this woven class's
   * class literal before calling the proxy load overload.
   */
  private void addLoadBridge(ClassVisitor writer, String name,
                             LoadBridge bridge)
  {
    MethodVisitor bridgeMethod =
      writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC |
                         Opcodes.ACC_SYNTHETIC,
                         name, bridge.bridgeDescriptor, null, null);
    bridgeMethod.visitCode();
    bridgeMethod.visitVarInsn(Opcodes.ALOAD, 0);
    if (bridge.argumentCount == 2)
      {
        bridgeMethod.visitVarInsn(Opcodes.ALOAD, 1);
      }
    bridgeMethod.visitLdcInsn(Type.getObjectType(wovenClassName));
    bridgeMethod.visitMethodInsn(Opcodes.INVOKESTATIC,
                                 OSGI_SERVICE_LOADER,
                                 "load",
                                 bridge.proxyDescriptor,
                                 false);
    bridgeMethod.visitInsn(Opcodes.ARETURN);
    bridgeMethod.visitMaxs(0, 0);
    bridgeMethod.visitEnd();
  }
}
