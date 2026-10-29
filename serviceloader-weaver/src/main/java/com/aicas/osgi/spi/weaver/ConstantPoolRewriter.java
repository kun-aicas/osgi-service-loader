/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.weaver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Remapper;

/**
 * Rewrites class names and type descriptors while preserving existing
 * constant-pool indices.
 *
 * Before changing a UTF-8 entry, checks that it is safe to modify.
 * Rejects the transformation if an unknown attribute prevents that check,
 * or if an entry requiring replacement is also used as ordinary text.
 * The input byte array is never modified.
 *
 * <p>Every use of a UTF-8 entry must be classified, but only class names
 * and type descriptors are candidates for remapping:
 * {@code CONSTANT_Class.name_index},
 * {@code CONSTANT_NameAndType.descriptor_index}, and
 * {@code CONSTANT_MethodType.descriptor_index} .
 *
 * String literals, module names, and package names are protected
 * from modification.</p>
 *
 * <p>Field references, method references, and method handles are updated
 * indirectly through the class and name-and-type entries they reference;
 * their constant-pool indices remain unchanged. A UTF-8 entry shared by a
 * type requiring replacement and protected text causes the transformation
 * to be rejected, since changing that entry would change both uses.</p>
 */
final class ConstantPoolRewriter
{
  /*----------------------- classes and enums -------------------------*/
  /** Expected rejection; no partial output is returned. */
  static final class UnsafeConstantPoolException extends RuntimeException
  {
    /** Serialization version for this exception type. */
    private static final long serialVersionUID = 1L;
  }

  /*--------------------------- variables -----------------------------*/

  /** Original class-file bytes, retained without modification for later output. */
  private final byte[] bytes;

  /** Mapping for class names, descriptors, and signatures. */
  private final Remapper remapper;

  /**
   * Encoded constant-pool entries indexed by their JVM indices. Index zero
   * and reserved second slots of long and double constants contain null.
   */
  private final List<byte[]> constantPoolEntries = new ArrayList<>();

  /**
   * Decoded UTF-8 text at original constant-pool indices; other slots are null.
   */
  private final String[] utf8TextByIndex;

  /** Marks UTF-8 indices whose text must remain unchanged. */
  private final boolean[] protectedUtf8Indices;

  /** Proposed replacement text keyed by original UTF-8 constant-pool index. */
  private final Map<Integer, String> replacements = new HashMap<>();

  /** Byte offset immediately after the original constant pool. */
  private final int poolEnd;

  /** Constant-pool index identifying the class being transformed. */
  private final int thisClassIdx;

  /**
   * Whether the class being transformed is an interface, determining the
   * method-reference tag used for generated bridge calls.
   */
  private final boolean isInterface;

  /*------------------------  constructors  ---------------------------*/

  /**
   * Parses the supplied class file and prepares replacements that can be
   * applied without changing existing constant-pool indices.
   *
   * <p>Copies the constant-pool entries, preserving the reserved second slots
   * of long and double constants, and decodes their UTF-8 text. Inspects the
   * class structure, fields, methods, and attributes to identify type text
   * that needs remapping and ordinary text that must remain unchanged.</p>
   *
   * <p>Records proposed replacements and rejects entries that also serve as
   * protected ordinary text, or structures whose references cannot be safely
   * classified. The input array is never modified; transformed bytes are
   * produced later by {@link #rewriteConstantPool(Function)}.</p>
   *
   * @param bytes class-file bytes to inspect and retain for later output.
   * @param remapper mapping for class names, descriptors, and signatures.
   * @throws IllegalArgumentException if malformed or truncated class-file
   *         data is detected.
   * @throws UnsafeConstantPoolException if safe rewriting cannot be
   *         established, including unknown attributes or conflicting uses
   *         of a UTF-8 entry.
   */
  ConstantPoolRewriter(byte[] bytes, Remapper remapper)
  {
    this.bytes = bytes;
    this.remapper = remapper;
    try
      {
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        skip(input, 8);
        int cpoolCount = Short.toUnsignedInt(input.getShort());
        utf8TextByIndex = new String[cpoolCount];
        protectedUtf8Indices = new boolean[cpoolCount];
        constantPoolEntries.add(null);
        for (int cpoolIndix = 1; cpoolIndix < cpoolCount; cpoolIndix++)
          {
            int start = input.position();
            int tag = Byte.toUnsignedInt(input.get());
            switch (tag)
              {
              case 1: // CONSTANT_Utf8
                int size = Short.toUnsignedInt(input.getShort());
                skip(input, size);
                try
                  {
                    utf8TextByIndex[cpoolIndix] =
                      new DataInputStream(new ByteArrayInputStream(bytes,
                                                                   start + 1,
                                                                   size + 2)).readUTF();
                  }
                catch (IOException exception)
                  {
                    throw new IllegalArgumentException("Invalid modified UTF-8",
                                                       exception);
                  }
                break;
              case 3: // CONSTANT_Integer
              case 4: // CONSTANT_Float
              case 9: // CONSTANT_Fieldref(CONSTANT_Class.CONSTANT_NameAndType)
              case 10: // CONSTANT_Methodref(CONSTANT_Class.CONSTANT_NameAndType)
              case 11: // CONSTANT_InterfaceMethodref(CONSTANT_Class.CONSTANT_NameAndType)
              case 12: // CONSTANT_NameAndType (CONSTANT_Utf8.CONSTANT_Utf8)
              case 17: // CONSTANT_Dynamic
              case 18: // CONSTANT_InvokeDynamic
                skip(input, 4);
                break;
              case 5: // CONSTANT_Long
              case 6: // CONSTANT_Double
                skip(input, 8);
                break;
              case 7: // CONSTANT_Class (CONSTANT_Utf8)
              case 8: // CONSTANT_String (CONSTANT_Utf8)
              case 16: // CONSTANT_MethodType (CONSTANT_Utf8)
              case 19: // CONSTANT_Module (CONSTANT_Utf8)
              case 20: // CONSTANT_Package (CONSTANT_Utf8)
                skip(input, 2);
                break;
              case 15: // CONSTANT_MethodHandle(reference_kind.CONSTANT_Fieldref/CONSTANT_Methodref/CONSTANT_InterfaceMethodref)
                skip(input, 3);
                break;
              default:
                throw new IllegalArgumentException("Unknown constant-pool tag: " +
                                                   tag);
              }
            constantPoolEntries.add(Arrays.copyOfRange(bytes, start, input.position()));
            // CONSTANT_Long and CONSTANT_Double occupy two constant-pool slots;
            // the second slot is reserved and has no bytes in the class file.
            if (tag == 5 || tag == 6)
              {
                if (++cpoolIndix >= cpoolCount)
                  {
                    throw new IllegalArgumentException("Missing long/double reserved slot");
                  }
                constantPoolEntries.add(null);
              }
          }
        poolEnd = input.position();
        isInterface = (Short.toUnsignedInt(input.getShort()) &
                          Opcodes.ACC_INTERFACE) != 0;
        thisClassIdx = Short.toUnsignedInt(input.getShort());
        // skip super_class.
        skip(input, 2);
        // skip interfaces_count and interfaces[interfaces_count]
        skip(input, 2L * Short.toUnsignedInt(input.getShort()));
        classifyCPool();
        members(input, true); // fields_count + fields[]
        members(input, false); // methods_count + methods[]
        attributes(input, false);
        if (input.position() != bytes.length)
          {
            throw new IllegalArgumentException("Trailing class-file bytes");
          }
        for (int index : replacements.keySet())
          {
            if (protectedUtf8Indices[index])
              {
                throw new UnsafeConstantPoolException();
              }
          }
      }
    catch (BufferUnderflowException exception)
      {
        throw new IllegalArgumentException("Truncated class file", exception);
      }
  }

  /*---------------------------- methods ------------------------------*/
  /**
   * Checks the requested length without advancing the buffer.
   *
   * @param input class-file buffer at the current parsing position
   * @param size number of bytes to check, before narrowing to an int
   * @return exclusive end offset of the requested bytes
   * @throws IllegalArgumentException if size is negative or exceeds the
   *         remaining bytes
   */
  private static int checkedEndOffset(ByteBuffer input, long size)
  {
    if (size < 0 || size > input.remaining())
      {
        throw new IllegalArgumentException("Truncated class file");
      }
    return input.position() + (int) size;
  }

  /**
   * Advances past the requested bytes after checking their availability.
   *
   * @param input class-file buffer to advance
   * @param size number of bytes to skip
   * @throws IllegalArgumentException if size is negative or exceeds the
   *         remaining bytes
   */
  private static void skip(ByteBuffer input, long size)
  {
    input.position(checkedEndOffset(input, size));
  }

  /**
   * Classifies constant-pool references to UTF-8 entries. Class references
   * schedule replacements, while string literals protect their referenced
   * entries from modification.
   *
   * <p>For example, these entries share the same UTF-8 index:</p>
   * <pre>
   * #10 = Utf8   java/util/ServiceLoader
   * #11 = Class  #10
   * #12 = String #10
   * </pre>
   * <p>Remapping the class would also change the string literal. After
   * classification, the constructor detects that index 10 is both scheduled
   * for replacement and protected, and throws
   * {@link UnsafeConstantPoolException}. Equal text stored in separate UTF-8
   * entries does not cause this conflict.</p>
   */
  private void classifyCPool()
  {
    for (int index = 1; index < utf8TextByIndex.length; index++)
      {
        byte[] entry = constantPoolEntries.get(index);
        if (entry == null)
          {
            continue;
          }
        switch (entry[0])
          {
          case 7: // CONSTANT_Class
            int nameIndex = readUnsignedShort(entry, 1);
            replace(nameIndex, remapper.mapType(getUtf8String(nameIndex)));
            break;
          case 8: // CONSTANT_String
          case 19: // CONSTANT_Module
          case 20: // CONSTANT_Package
            protect(readUnsignedShort(entry, 1));
            break;
          case 12: // CONSTANT_NameAndType
            descriptor(readUnsignedShort(entry, 3));
            break;
          case 16: // CONSTANT_MethodType
            descriptor(readUnsignedShort(entry, 1));
            break;
          default:
            break;
          }
      }
  }

  /**
   * Consumes a field or method table, skipping member names and remapping
   * its descriptors and attributes.
   *
   * @param input buffer positioned at the member count; advanced past the table.
   * @param fields true for fields and their type signatures, false for methods.
   * @throws IllegalArgumentException if invalid referenced text or lengths are detected.
   * @throws UnsafeConstantPoolException if attributes cannot be safely classified.
   * @throws BufferUnderflowException if a primitive read exceeds the remaining bytes.
   */
  private void members(ByteBuffer input, boolean fields)
  {
    int count = Short.toUnsignedInt(input.getShort());
    for (int index = 0; index < count; index++)
      {
        skip(input, 2); // access_flags
        skip(input, 2); // name_index
        descriptor(Short.toUnsignedInt(input.getShort()));
        attributes(input, fields);
      }
  }

  /**
   * Consumes an attribute table, recursively inspecting known structures for
   * UTF-8 references. Protects ordinary text, schedules type replacements, and
   * checks each attribute's consumed length. Unknown attributes are rejected.
   *
   * @param input buffer positioned at the attribute count; advanced past the table.
   * @param typeSignature true for a type signature, false for a class or method
   *        signature when remapping a Signature attribute.
   * @throws IllegalArgumentException if invalid text, tags, or lengths are detected.
   * @throws UnsafeConstantPoolException if an attribute cannot be safely classified.
   * @throws BufferUnderflowException if a primitive read exceeds the remaining bytes.
   */
  private void attributes(ByteBuffer input, boolean typeSignature)
  {
    int count = Short.toUnsignedInt(input.getShort());
    for (int index = 0; index < count; index++)
      {
        int attNameIndex = Short.toUnsignedInt(input.getShort()); // attribute name index
        String attName = getUtf8String(attNameIndex);
        long length = Integer.toUnsignedLong(input.getInt());
        int end = checkedEndOffset(input, length);
        switch (attName)
          {
          case "Signature":// class types within the signature are remapped, but the signature itself is not changed
            int signature = Short.toUnsignedInt(input.getShort());
            replace(signature,
                    remapper.mapSignature(getUtf8String(signature), typeSignature));
            break;
          case "SourceFile":
            protect(Short.toUnsignedInt(input.getShort()));
            break;
          case "Code":
            skip(input, 4); // max_stack, max_locals
            skip(input, Integer.toUnsignedLong(input.getInt()));
            skip(input, 8L * Short.toUnsignedInt(input.getShort())); // exception table
            attributes(input, false);
            break;
          case "LocalVariableTable":
          case "LocalVariableTypeTable":
            int variables = Short.toUnsignedInt(input.getShort());
            for (int variable = 0; variable < variables; variable++)
              {
                skip(input, 4);
                skip(input, 2); // name_index
                int type = Short.toUnsignedInt(input.getShort()); // descriptor_index or signature_index
                if (attName.equals("LocalVariableTypeTable"))
                  {
                    replace(type, remapper.mapSignature(getUtf8String(type), true));
                  }
                else
                  {
                    descriptor(type);
                  }
                skip(input, 2);
              }
            break;
          case "InnerClasses":
            int classes = Short.toUnsignedInt(input.getShort());
            skip(input, 8L * classes);
            break;
          case "MethodParameters":
            int parameters = Byte.toUnsignedInt(input.get());
            skip(input, 4L * parameters); // name_index, access_flags
            break;
          case "RuntimeVisibleAnnotations":
          case "RuntimeInvisibleAnnotations":
            annotations(input);
            break;
          case "RuntimeVisibleParameterAnnotations":
          case "RuntimeInvisibleParameterAnnotations":
            int annotatedParameters = Byte.toUnsignedInt(input.get());
            for (int parameter = 0; parameter < annotatedParameters; parameter++)
              {
                annotations(input);
              }
            break;
          case "RuntimeVisibleTypeAnnotations":
          case "RuntimeInvisibleTypeAnnotations":
            int typeAnnotations = Short.toUnsignedInt(input.getShort());
            for (int annotation = 0; annotation < typeAnnotations; annotation++)
              {
                skipTypeAnnotationTarget(input);
                skip(input, 2L * Byte.toUnsignedInt(input.get())); // type_path
                annotation(input);
              }
            break;
          case "AnnotationDefault":
            elementValue(input);
            break;
          case "Record":
            int components = Short.toUnsignedInt(input.getShort());
            for (int component = 0; component < components; component++)
              {
                skip(input, 2); // name_index
                descriptor(Short.toUnsignedInt(input.getShort()));
                attributes(input, true);
              }
            break;
          // These attributes have no direct UTF-8 references in their payload.
          // Their indirect references are covered by classifying the pool.
          case "ConstantValue":
          case "StackMapTable":
          case "Exceptions":
          case "EnclosingMethod":
          case "Synthetic":
          case "SourceDebugExtension":
          case "LineNumberTable":
          case "Deprecated":
          case "BootstrapMethods":
          case "ModulePackages":
          case "ModuleMainClass":
          case "NestHost":
          case "NestMembers":
          case "PermittedSubclasses":
            skip(input, length);
            break;
          default:
            throw new UnsafeConstantPoolException();
          }
        if (input.position() != end)
          {
            throw new IllegalArgumentException("Invalid attribute length: " +
                                               attName);
          }
      }
  }

  /**
   * Consumes a counted annotation table, remapping types and protecting
   * string values through the annotation parser.
   *
   * @param input buffer positioned at the annotation count; advanced past the table
   * @throws IllegalArgumentException if invalid referenced text, tags, or lengths
   *        are detected
   * @throws BufferUnderflowException if a primitive read exceeds the remaining bytes
   */
  private void annotations(ByteBuffer input)
  {
    int count = Short.toUnsignedInt(input.getShort());
    for (int index = 0; index < count; index++)
      {
        annotation(input);
      }
  }

  /**
   * Consumes one annotation, remapping its type descriptor and inspecting
   * its element values while skipping element names.
   *
   * @param input buffer positioned at the annotation type index; advanced past
   *        the annotation
   * @throws IllegalArgumentException if invalid referenced text, tags, or lengths
   *        are detected
   * @throws BufferUnderflowException if a primitive read exceeds the remaining bytes
   */
  private void annotation(ByteBuffer input)
  {
    descriptor(Short.toUnsignedInt(input.getShort()));
    int pairs = Short.toUnsignedInt(input.getShort());
    for (int pair = 0; pair < pairs; pair++)
      {
        skip(input, 2); // element_name_index
        elementValue(input);
      }
  }

  /**
   * Consumes one annotation element value, recursively handling nested
   * annotations and arrays. Remaps class and enum type descriptors and
   * protects string values and skips enum constant names.
   *
   * @param input buffer positioned at the value tag; advanced past the value.
   * @throws IllegalArgumentException if invalid referenced text, tags, or lengths
   *        are detected.
   * @throws BufferUnderflowException if a primitive read exceeds the remaining bytes.
   */
  private void elementValue(ByteBuffer input)
  {
    int tag = Byte.toUnsignedInt(input.get());
    switch (tag)
      {
      case 's': // string
        protect(Short.toUnsignedInt(input.getShort()));
        break;
      case 'e': // enum constant
        descriptor(Short.toUnsignedInt(input.getShort()));
        skip(input, 2); // const_name_index
        break;
      case 'c': // class
        descriptor(Short.toUnsignedInt(input.getShort()));
        break;
      case '@': // nested annotation
        annotation(input);
        break;
      case '[': // array of values
        int count = Short.toUnsignedInt(input.getShort());
        for (int index = 0; index < count; index++)
          {
            elementValue(input);
          }
        break;
      case 'B': // byte
      case 'C': // char
      case 'D': // double
      case 'F': // float
      case 'I': // int
      case 'J': // long
      case 'S': // short
      case 'Z': // boolean
        skip(input, 2);
        break;
      default:
        throw new IllegalArgumentException("Invalid annotation value tag: " +
                                           tag);
      }
  }

  /**
   * Reads the target type and skips the target-specific data of a type annotation.
   * Leaves the buffer at the following type path; target data contains no
   * direct UTF-8 references to classify.
   *
   * @param input buffer positioned at the target type.
   * @throws IllegalArgumentException if the target type is unknown or its data
   *        exceeds the remaining bytes.
   * @throws BufferUnderflowException if a primitive read exceeds the remaining bytes.
   */
  private void skipTypeAnnotationTarget(ByteBuffer input)
  {
    int target = Byte.toUnsignedInt(input.get());
    switch (target)
      {
      case 0x00:
      case 0x01:
      case 0x16:
        skip(input, 1);
        break;
      case 0x10:
      case 0x11:
      case 0x12:
      case 0x17:
      case 0x42:
      case 0x43:
      case 0x44:
      case 0x45:
      case 0x46:
        skip(input, 2);
        break;
      case 0x13:
      case 0x14:
      case 0x15:
        break;
      case 0x40:
      case 0x41:
        skip(input, 6L * Short.toUnsignedInt(input.getShort()));
        break;
      case 0x47:
      case 0x48:
      case 0x49:
      case 0x4a:
      case 0x4b:
        skip(input, 3);
        break;
      default:
        throw new IllegalArgumentException("Invalid type annotation target: " +
                                           target);
      }
  }

  /**
   * Returns decoded text from an original CONSTANT_Utf8 entry.
   *
   * @param index original constant-pool index.
   * @return decoded modified UTF-8 text.
   * @throws IllegalArgumentException if the index does not identify a UTF-8 entry.
   */
  private String getUtf8String(int index)
  {
    if (index <= 0 || index >= utf8TextByIndex.length || utf8TextByIndex[index] == null)
      {
        throw new IllegalArgumentException("Invalid UTF-8 index: " + index);
      }
    return utf8TextByIndex[index];
  }

  /**
   * Marks a UTF-8 entry as ordinary text that must remain unchanged.
   * Conflicts with proposed replacements are checked after classification.
   *
   * @param index original UTF-8 constant-pool index
   * @throws IllegalArgumentException if the index does not identify a UTF-8 entry
   */
  private void protect(int index)
  {
    getUtf8String(index);
    protectedUtf8Indices[index] = true;
  }

  /**
   * Schedules remapping of a field or method descriptor stored as UTF-8 text.
   * A leading opening parenthesis identifies a method descriptor.
   *
   * @param index original UTF-8 index containing the descriptor
   * @throws IllegalArgumentException if the index is not a UTF-8 entry or the
   *        remapper rejects the descriptor
   * @throws UnsafeConstantPoolException if conflicting replacements are requested
   */
  private void descriptor(int index)
  {
    String value = getUtf8String(index);
    replace(index, value.startsWith("(") ? remapper.mapMethodDesc(value)
                                         : remapper.mapDesc(value));
  }

  /**
   * Records a replacement only when it differs from the original text.
   * Repeated requests must agree; protected-text conflicts are checked after
   * classification.
   *
   * @param index original UTF-8 constant-pool index.
   * @param value proposed replacement text.
   * @throws IllegalArgumentException if the index does not identify a UTF-8 entry.
   * @throws UnsafeConstantPoolException if a different replacement was already
   *        recorded for this index.
   */
  private void replace(int index, String value)
  {
    String original = getUtf8String(index);
    if (!original.equals(value))
      {
        String previous = replacements.putIfAbsent(index, value);
        if (previous != null && !previous.equals(value))
          {
            throw new UnsafeConstantPoolException();
          }
      }
  }

  /**
   * Applies the prepared UTF-8 replacements, redirects supported JDK ServiceLoader load
   * method references to bridge method on the current class, appending bridge names
   * and NameAndType entries as needed, and then writes a new constant pool followed by the
   * unchanged remainder of the original class file.
   *
   * <p>Updates the internally stored entries but never modifies the input array.</p>
   *
   * @param bridgeName callback mapping an original supported load descriptor to
   *        its generated bridge method name.
   * @return newly allocated transformed class-file bytes.
   * @throws IllegalArgumentException if referenced text is invalid, the constant
   *        pool is full, or encoding or output fails.
   */
  byte[] rewriteConstantPool(Function<String, String> bridgeName)
  {
    replacements.forEach((index, value) -> constantPoolEntries.set(index,
                                                                   encodeUtf(value)));
    // Keep original Methodref indices so instructions and MethodHandles need
    // no changes. Append separate NameAndType entries rather than renaming a
    // shared "load" UTF-8 or NameAndType entry.
    for (int index = 1; index < utf8TextByIndex.length; index++)
      {
        byte[] entry = constantPoolEntries.get(index);
        if (entry == null || entry[0] != 10) // CONSTANT_Methodref
          {
            continue;
          }
        byte[] owner = constantPoolEntries.get(readUnsignedShort(entry, 1));
        byte[] nameAndType = constantPoolEntries.get(readUnsignedShort(entry, 3));
        String ownerName = getUtf8String(readUnsignedShort(owner, 1));
        int descriptor = readUnsignedShort(nameAndType, 3);
        String methodDescriptor = getUtf8String(descriptor);
        /**
         *   #23 = Methodref          #24.#25        // java/util/ServiceLoader.load:(Ljava/lang/Class;)Ljava/util/ServiceLoader;
         *   #24 = Class              #26            // java/util/ServiceLoader
         *   #25 = NameAndType        #27:#28        // load:(Ljava/lang/Class;)Ljava/util/ServiceLoader;
         *   #26 = Utf8               java/util/ServiceLoader
         *   #27 = Utf8               load
         *   #28 = Utf8               (Ljava/lang/Class;)Ljava/util/ServiceLoader;
         *
         *   replace with
         *
         *   #23 = Methodref          #61.#85        // ServiceloaderConsumer.serviceLoaderBridge$load:(Ljava/lang/Class;)Lcom/aicas/osgi/spi/proxy/ServiceLoader;
         *   #24 = Class              #26            // com/aicas/osgi/spi/proxy/ServiceLoader
         *   #25 = NameAndType        #27:#28        // load:(Ljava/lang/Class;)Lcom/aicas/osgi/spi/proxy/ServiceLoader;
         *   #26 = Utf8               com/aicas/osgi/spi/proxy/ServiceLoader
         *   #27 = Utf8               load
         *   #28 = Utf8               (Ljava/lang/Class;)Lcom/aicas/osgi/spi/proxy/ServiceLoader;
         *   #61 = Class              #62            // ServiceloaderConsumerActivator
         *   #62 = Utf8               ServiceloaderConsumerActivator
         *   #84 = Utf8               serviceLoaderBridge$load
         *   #85 = NameAndType        #84:#28        // serviceLoaderBridge$load:(Ljava/lang/Class;)Lcom/aicas/osgi/spi/proxy/ServiceLoader;
         */
        if (WeaverConstants.JAVA_SERVICE_LOADER.equals(ownerName) &&
            "load".equals(getUtf8String(readUnsignedShort(nameAndType, 1))) &&
            (WeaverConstants.JAVA_LOAD_DESCRIPTOR.equals(methodDescriptor) ||
             WeaverConstants.JAVA_LOAD_WITH_LOADER_DESCRIPTOR.equals(methodDescriptor)))
          {
            int name = appendUtf(bridgeName.apply(methodDescriptor));
            int bridgeType = append(pair(12, name, descriptor)); // CONSTANT_NameAndType
            // Use CONSTANT_InterfaceMethodref (11) for an interface owner,
            // otherwise CONSTANT_Methodref (10).
            constantPoolEntries.set(index, pair(isInterface ? 11 : 10, thisClassIdx, bridgeType));
          }
      }
    try
      {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(buffer);
        output.write(bytes, 0, 8);
        output.writeShort(constantPoolEntries.size());
        for (byte[] entry : constantPoolEntries)
          {
            if (entry != null)
              {
                output.write(entry);
              }
          }
        // Write the unchanged remainder of the original class file.
        output.write(bytes, poolEnd, bytes.length - poolEnd);
        return buffer.toByteArray();
      }
    catch (IOException exception)
      {
        throw new IllegalArgumentException("Cannot write constant pool",
                                           exception);
      }
  }

  /**
   * Reuses an identical encoded UTF-8 entry if present, or appends a new one.
   *
   * @param value text to encode as a CONSTANT_Utf8 entry.
   * @return constant-pool index of the existing or appended entry.
   * @throws IllegalArgumentException if encoding fails or the constant pool is full.
   */
  private int appendUtf(String value)
  {
    byte[] encoded = encodeUtf(value);
    for (int index = 1; index < constantPoolEntries.size(); index++)
      {
        if (Arrays.equals(encoded, constantPoolEntries.get(index)))
          {
            return index;
          }
      }
    return append(encoded);
  }

  /**
   * Appends an encoded constant-pool entry without moving existing entries.
   *
   * @param entry complete encoded entry, including its tag.
   * @return constant-pool index assigned to the entry.
   * @throws IllegalArgumentException if the constant pool is full.
   */
  private int append(byte[] entry)
  {
    if (constantPoolEntries.size() >= 65535)
      {
        throw new IllegalArgumentException("Constant pool is full");
      }
    int index = constantPoolEntries.size();
    constantPoolEntries.add(entry);
    return index;
  }

  /**
   * Encodes a complete CONSTANT_Utf8 entry using modified UTF-8, including
   * its tag and two-byte encoded length.
   *
   * @param value text to encode.
   * @return newly allocated encoded entry.
   * @throws IllegalArgumentException if the modified UTF-8 representation
   *        exceeds the class-file length limit or encoding fails.
   */
  private static byte[] encodeUtf(String value)
  {
    try
      {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(buffer);
        output.writeByte(1); // CONSTANT_Utf8
        output.writeUTF(value);
        return buffer.toByteArray();
      }
    catch (IOException exception)
      {
        throw new IllegalArgumentException("UTF-8 constant exceeds class-file limits",
                                           exception);
      }
  }

  /**
   * Encodes a constant-pool entry consisting of a tag and two unsigned
   * big-endian two-byte indices. Arguments are expected to fit their fields.
   *
   * @param tag constant-pool entry tag.
   * @param first first constant-pool index.
   * @param second second constant-pool index.
   * @return newly allocated five-byte entry.
   */
  private static byte[] pair(int tag, int first, int second)
  {
    return new byte[]{ (byte) tag, (byte) (first >>> 8), (byte) first,
                       (byte) (second >>> 8), (byte) second };
  }

  /**
   * Reads an unsigned big-endian two-byte value at an absolute offset.
   * Does not maintain or advance a parsing position.
   *
   * @param data encoded bytes containing the value.
   * @param offset index of the first byte.
   * @return value in the range 0 to 65535.
   * @throws ArrayIndexOutOfBoundsException if either byte is outside the array.
   */
  private static int readUnsignedShort(byte[] data, int offset)
  {
    return (data[offset] & 255) << 8 | data[offset + 1] & 255;
  }

}
