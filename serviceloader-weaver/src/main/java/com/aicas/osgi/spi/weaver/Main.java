/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.weaver;

import static com.aicas.osgi.spi.weaver.WeaverConstants.BUNDLE_CLASSPATH;
import static com.aicas.osgi.spi.weaver.WeaverConstants.BUNDLE_SYMBOLIC_NAME;
import static com.aicas.osgi.spi.weaver.WeaverConstants.IMPORT_PACKAGE;
import static com.aicas.osgi.spi.weaver.WeaverConstants.PROXY_IMPORT;
import static com.aicas.osgi.spi.weaver.WeaverConstants.PROXY_PACKAGE;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Statically weaves a JAR archive without changing the input JAR. */
public final class Main
{

  /**
   * Archive entry metadata and in-memory content used for scanning, weaving,
   * and rebuilding an archive.
   *
   * <p>The content can be replaced after successful transformation. For an
   * embedded JAR, it contains the complete JAR bytes rather than its expanded
   * entries. Directory entries have empty content.</p>
   */
  private static final class Entry
  {
    /** Entry path within the archive. */
    private final String name_;

    /** Whether this entry represents a directory. */
    private final boolean directory_;

    /** Original or transformed file content; an empty array for directories. */
    private byte[] bytes_;

    /**
     * Creates an entry with the supplied metadata and content.
     *
     * @param name entry path within the archive.
     * @param directory whether the entry represents a directory.
     * @param bytes file content, or an empty array for a directory; embedded
     *              JAR content consists of the complete JAR bytes.
     */
    Entry(String name, boolean directory, byte[] bytes)
    {
      name_ = name;
      directory_ = directory;
      bytes_ = bytes;
    }
  }

  private final List<String> wovenClasses = new ArrayList<String>();
  private final List<String> unsupportedClasses = new ArrayList<String>();

  private Main()
  {
  }

  /**
   * Executes {@code java -jar ... input.jar [output-directory]}.
   *
   * <p>The output directory must already exist and defaults to the current
   * working directory. The generated filename uses the input basename followed
   * by {@code -spi-consumer.jar}. Invalid argument counts terminate with exit
   * status {@code 2}; processing exceptions print a message and stack trace to
   * standard error and terminate with exit status {@code 1}. Skipped bundles
   * return normally without producing output.</p>
   *
   * @param arguments input bundle path followed optionally by an existing
   *                  output directory.
   */
  public static void main(String[] arguments)
  {
    int len = arguments.length;
    if (len < 1 || len > 2)
      {
        System.err.println("Usage: java -jar serviceloader-static-weaver-<version>.jar " +
                     "<input-bundle.jar> [output-directory]");
        System.exit(2);
        return;
      }
    try
      {
        Path input = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path outputDirectory =
          len == 2 ? Path.of(arguments[1]).toAbsolutePath().normalize()
                   : Path.of(".").toAbsolutePath().normalize();

        if (!Files.isDirectory(outputDirectory))
          {
            throw new IOException("Output directory does not exist or is not a directory: " +
                                  outputDirectory);
          }
        weave(input, outputDirectory);
      }
    catch (Exception exception)
      {
        System.err.println("Static weaving failed: " + exception.getMessage());
        exception.printStackTrace(System.err);
        System.exit(1);
      }
  }

  /**
   * Derives an output path by removing the input filename's final extension,
   * if present, and appending {@code -spi-consumer.jar}.
   *
   * <p>This method does not validate or create the destination directory.</p>
   *
   * @param input input bundle path used to derive the output filename.
   * @param outputDirectory directory in which to place the generated archive.
   * @return generated filename resolved against {@code outputDirectory}.
   * @throws IllegalArgumentException if {@code input} has no file name.
   */
  static Path outputPath(Path input, Path outputDirectory)
  {
    Path fileName = input.getFileName();
    if (fileName == null)
      {
        throw new IllegalArgumentException("Input bundle must name a file: " +
                                           input);
      }
    String name = fileName.toString();
    int extension = name.lastIndexOf('.');
    String baseName = extension > 0 ? name.substring(0, extension) : name;
    return outputDirectory.resolve(baseName + "-spi-consumer.jar");
  }

  /**
   * Scans and weaves an archive, then publishes an output JAR with the
   * {@code -spi-consumer.jar} filename suffix without modifying the input.
   *
   * <p>Classes stored directly in the outer archive are processed first,
   * followed by classes in embedded JARs specified in {@code Bundle-ClassPath}.
   * Embedded JARs are not processed recursively. Successfully woven and
   * unsupported class-entry names are collected in instance lists. Unsupported
   * invocations retain their original class bytes; transformation errors are
   * propagated immediately.</p>
   *
   * <p>The outer manifest receives {@link WeaverConstants#STATIC_WEAVING_HEADER}
   * with value {@code woven} when at least one class was woven, or {@code scanned}
   * otherwise. The proxy package import is added only when a class was woven.
   * If the input has no manifest, one is created with {@code Manifest-Version: 1.0}.
   * Resources are retained.</p>
   *
   * <p>Infrastructure bundles and archives carrying a valid processing status
   * are skipped with a message and no output changes. At the processing-header check, an invalid
   * status is rejected with {@link IllegalArgumentException}. </p>
   *
   * <p>Signatures are not verified in the outer archive or embedded JARs.
   * Existing signature metadata is retained and may be invalidated by weaving.
   * Remove existing signature metadata and sign the generated JAR before
   * installation.</p>
   *
   * <p>Existing output is deleted after a temporary JAR has been successfully
   * written and closed. After publication and closure of the input archive,
   * the destination and nonempty class lists({@link #wovenClasses} ,
   * {@link #unsupportedClasses}) are printed to standard output.</p>
   *
   * <p>The command-line entry point requires an existing output directory;
   * specified as an absolute path or a path relative to the current directory.
   * If omitted, the output is written to the current directory. </p>
   *
   * @param input existing JAR archive to process.
   * @param outputDirectory directory in which to publish the generated JAR.
   * @throws Exception if input reading, class transformation, archive writing,
   *                   destination deletion, publication, or resource cleanup fails.
   */
  public static void weave(Path input, Path outputDirectory)
    throws Exception
  {
    new Main().process(input, outputDirectory);
  }

  /**
   * Processes one archive using this instance's class-name lists.
   *
   * @param input existing JAR archive to process.
   * @param outputDirectory directory for the generated JAR.
   * @throws Exception if validation, scanning, transformation, or publication fails.
   */
  private void process(Path input, Path outputDirectory)
    throws Exception
  {
    if (!Files.isRegularFile(input))
      {
        throw new IOException("Input bundle does not exist or is not a file: " +
                              input);
      }
    Path output = outputPath(input, outputDirectory);
    try (JarFile jar = new JarFile(input.toFile(), false))
      {
        Manifest manifest = jar.getManifest();
        if (manifest == null)
          {
            manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
          }

        // Reject the infrastructure bundles.
        String symbolicName = bundleSymbolicName(manifest);
        if (symbolicName != null &&
            WeaverConstants.isInfrastructureBundle(symbolicName))
          {
            System.out.println("Refusing to weave infrastructure bundle - " +
                               symbolicName + "; no output was created.");
            return;
          }

        // Validate an existing status before skipping a processed bundle.
        String staticStatus = manifest.getMainAttributes().
            getValue(WeaverConstants.STATIC_WEAVING_HEADER);
        if (staticStatus != null)
          {
            if (!StaticWeavingStatus.isValid(staticStatus))
              {
                throw new IllegalArgumentException(
                    "Invalid " + WeaverConstants.STATIC_WEAVING_HEADER +
                    " value '" + staticStatus + "' in bundle " + input +
                    (symbolicName == null ? "" : " (" + symbolicName + ")") +
                    "; expected 'woven' or 'scanned'");
              }
            System.out.println("Refusing to weave already statically processed bundle " +
                               input + "; no output was created");
            return;
          }

        List<Entry> entries = readEntries(jar);

        weaveEntries(entries);

        weaveBundleClassPath(entries, manifest);

        Manifest outputManifest = new Manifest(manifest);
        if (!wovenClasses.isEmpty())
          {
            addProxyImport(outputManifest);
            outputManifest.getMainAttributes().
                           putValue(WeaverConstants.STATIC_WEAVING_HEADER,
                                    StaticWeavingStatus.WOVEN.getValue());
          }
        else
          {
            outputManifest.getMainAttributes().
                           putValue(WeaverConstants.STATIC_WEAVING_HEADER,
                                    StaticWeavingStatus.SCANNED.getValue());
          }
        writeAtomically(output, outputManifest, entries);
      }
    System.out.println((wovenClasses.isEmpty() ? "Successfully scanned bundle ["
                                               : "Successfully  woven  bundle [") +
                                      input + "]\nDestination                 [" + output + "]");
    if(!wovenClasses.isEmpty())
      {
        printClassList("Woven classes", wovenClasses);
      }
    else
      {
        System.out.println("Bundle scanned; no classes were woven.");
      }
    if(!unsupportedClasses.isEmpty())
      {
        printClassList("Unsupported classes", unsupportedClasses);
      }
  }

  /**
   * Prints a labeled class-entry list and its count to standard output.
   *
   * @param label heading identifying the list.
   * @param classes class-entry paths to print in their recorded order.
   */
  private static void printClassList(String label, List<String> classes)
  {
    System.out.println(label + " [" + classes.size() + "]:");
    for (String className : classes)
      {
        System.out.println(" -  " + className);
      }
  }

  /**
   * Returns the trimmed portion of {@code Bundle-SymbolicName} preceding the
   * first semicolon.
   *
   * @param manifest archive manifest.
   * @return symbolic name without following parameters, or {@code null} if absent.
   */
  private static String bundleSymbolicName(Manifest manifest)
  {
    String header = manifest.getMainAttributes().getValue(BUNDLE_SYMBOLIC_NAME);
    if (header == null)
      {
        return null;
      }
    int directive = header.indexOf(';');
    return (directive < 0 ? header : header.substring(0, directive)).trim();
  }

  /**
   * Reads all outer archive entries except {@code META-INF/MANIFEST.MF} into
   * memory, matching the manifest path without regard to case.
   *
   * <p>Embedded JARs are read as complete byte arrays, not expanded. Each entry
   * input stream is closed after reading; the caller retains ownership of the
   * {@code JarFile}. Entry timestamps are not retained.</p>
   *
   * @param jar source archive, which remains open.
   * @return entries containing archive paths, directory flags, and content.
   * @throws IOException if an entry stream cannot be opened, read, or closed.
   */
  private static List<Entry> readEntries(JarFile jar)
    throws IOException
  {
    List<Entry> entries = new ArrayList<>();
    Enumeration<JarEntry> jarEntries = jar.entries();
    while (jarEntries.hasMoreElements())
      {
        JarEntry entry = jarEntries.nextElement();
        if (JarFile.MANIFEST_NAME.equalsIgnoreCase(entry.getName()))
          {
            continue;
          }
        byte[] bytes;
        if (entry.isDirectory())
          {
            bytes = new byte[0];
          }
        else
          {
            try (InputStream input = jar.getInputStream(entry))
              {
                bytes = readAll(input);
              }
          }
        entries.add(new Entry(entry.getName(), entry.isDirectory(), bytes));
      }
    return entries;
  }

  /**
   * Reads the remaining entries from an archive stream into memory.
   *
   * <p>The manifest recognized by {@code JarInputStream} is available separately
   * through {@link JarInputStream#getManifest()}. This method does not explicitly
   * filter manifest entries returned by the stream. Embedded JAR bytes are not
   * expanded, entry timestamps are not retained, and the caller-owned stream
   * remains open.</p>
   *
   * @param input source stream positioned before the entries to read.
   * @return entries containing archive paths, directory flags, and content.
   * @throws IOException if advancing to or reading an entry fails.
   */
  private static List<Entry> readEntries(JarInputStream input)
    throws IOException
  {
    List<Entry> entries = new ArrayList<>();
    JarEntry jarEntry;
    while ((jarEntry = input.getNextJarEntry()) != null)
      {
        byte[] bytes = jarEntry.isDirectory() ? new byte[0] : readAll(input);
        entries.add(new Entry(jarEntry.getName(), jarEntry.isDirectory(), bytes));
      }
    return entries;
  }

  /**
   * Processes every non-directory entry whose name ends with {@code .class}.
   *
   * <p>Class content is updated after successful transformation, and woven or
   * unsupported class-entry names are recorded in the corresponding lists.
   * Embedded JAR entries are not expanded by this method.</p>
   *
   * @param entries mutable entries from one archive.
   * @return {@code true} if at least one class was transformed.
   * @throws Exception if a class cannot be parsed or transformed.
   */
  private boolean weaveEntries(List<Entry> entries)
    throws Exception
  {
    boolean woven = false;
    for (Entry entry : entries)
      {
        if (!entry.directory_ && entry.name_.endsWith(".class"))
          {
            woven |= weaveClass(entry);
          }
      }
    return woven;
  }

  /**
   * Processes embedded JAR entries named by the outer manifest's
   * {@code Bundle-ClassPath} header.
   *
   * <p>The header is split on commas and paths are trimmed. Empty paths,
   * {@code .}, directory entries, and paths with no matching archive entry are
   * ignored. Matching files are read as JAR streams and updated in place when
   * classes are woven. No deeper embedded JARs are processed.</p>
   *
   * @param entries mutable outer archive entries containing embedded JAR bytes.
   * @param manifest outer manifest supplying the bundle class path.
   * @throws Exception if an embedded archive cannot be read, transformed,
   *                   reconstructed, or closed.
   */
  private void weaveBundleClassPath(List<Entry> entries,
                                              Manifest manifest)
    throws Exception
  {
    String classPath = manifest.getMainAttributes().getValue(BUNDLE_CLASSPATH);
    if (classPath == null)
      {
        return;
      }
    //boolean woven = false;
    for (String classPathEntry : classPath.split(","))
      {
        String name = classPathEntry.trim();
        if (name.equals(".") || name.isEmpty())
          {
            continue;
          }
        for (Entry entry : entries)
          {
            if (entry.name_.equals(name) && !entry.directory_)
              {
                weaveNestedJar(entry);
                break;
              }
          }
      }
  }

  /**
   * Scans an embedded JAR in memory and replaces its content only when at
   * least one class was woven.
   *
   * <p>If no class is transformed, the original JAR bytes remain unchanged.
   * Otherwise, the recognized manifest and entry content are used to rebuild
   * the JAR without preserving timestamps or its original ZIP representation.
   * Replacement occurs after the output stream has successfully closed.
   * Signature verification is disabled and existing signature entries are retained.
   * Further embedded JARs are not scanned.</p>
   *
   * @param entry embedded JAR entry whose content may be replaced.
   * @throws Exception if reading, class transformation, reconstruction,
   *                   or stream closure fails.
   */
  private void weaveNestedJar(Entry entry)
    throws Exception
  {
    try (JarInputStream input = new JarInputStream(new ByteArrayInputStream(entry.bytes_), false))
      {
        Manifest manifest = input.getManifest();
        List<Entry> entries = readEntries(input);
        boolean woven = weaveEntries(entries);
        if (!woven)
          {
            return;
          }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jarOutput = manifest == null ? new JarOutputStream(output)
                                                          : new JarOutputStream(output,
                                                                                manifest))
          {
            writeEntries(jarOutput, entries);
          }
        entry.bytes_ = output.toByteArray();
      }
  }


  /**
   * Invokes the bytecode weaver for one class and records its outcome.
   *
   * <p>Woven bytes replace the entry content and its name is added to the woven
   * list. Unsupported invocations and unsafe constant-pool replacements add
   * the name to the unsupported list while retaining the original content.
   * Classes with no supported invocation remain
   * unchanged. Transformation errors are propagated immediately.</p>
   *
   * @param entry mutable class entry to process.
   * @return {@code true} if woven bytes replaced the entry content;
   *         {@code false} otherwise.
   * @throws Exception if the bytecode weaver reports a transformation error.
   */
  private boolean weaveClass(Entry entry)
    throws Exception
  {
    WeavingResult result = ServiceLoaderWeaver.weave(entry.bytes_);
    switch (result.getStatus())
      {
      case WOVEN:
        entry.bytes_ = result.getBytes();
        wovenClasses.add(entry.name_);
        return true;
      case NO_SUPPORTED_INVOCATION:
        return false;
      case UNSUPPORTED_INVOCATION:
      case UNSAFE_CONSTANT_POOL:
        unsupportedClasses.add(entry.name_);
        return false;
      case TRANSFORMATION_ERROR:
        throw result.getException();
      default:
        return false;
      }
  }

  /**
   * Adds the proxy package import when the manifest does not already contain
   * an import clause for it.
   *
   * @param manifest output bundle manifest.
   */
  private static void addProxyImport(Manifest manifest)
  {
    Attributes attributes = manifest.getMainAttributes();
    String imports = attributes.getValue(IMPORT_PACKAGE);
    if (imports == null || imports.isBlank())
      {
        attributes.putValue(IMPORT_PACKAGE, PROXY_IMPORT);
      }
    else if (!containsPackageClause(imports, PROXY_PACKAGE))
      {
        attributes.putValue(IMPORT_PACKAGE, imports + "," + PROXY_IMPORT);
      }
  }

  /**
   * Checks whether a comma-separated import header contains a package clause.
   *
   * <p>Quoted version ranges and filter expressions are ignored while locating
   * top-level commas.</p>
   *
   * @param header import-package header value.
   * @param packageName package name to find.
   * @return {@code true} if a matching clause exists.
   */
  static boolean containsPackageClause(String header, String packageName)
  {
    boolean quoted = false;
    int parentheses = 0;
    int start = 0;
    for (int index = 0; index <= header.length(); index++)
      {
        char character = index == header.length() ? ',' : header.charAt(index);
        if (character == '"')
          {
            quoted = !quoted;
          }
        else if (!quoted && character == '(')
          {
            parentheses++;
          }
        else if (!quoted && character == ')')
          {
            parentheses--;
          }
        else if (!quoted && parentheses == 0 && character == ',')
          {
            String clause = header.substring(start, index).trim();
            int separator = clause.indexOf(';');
            String name =
              separator < 0 ? clause : clause.substring(0, separator);
            if (packageName.equals(name.trim()))
              {
                return true;
              }
            start = index + 1;
          }
      }
    return false;
  }

  /**
   * Writes an archive to a sibling temporary file and then publishes it at
   * the destination.
   *
   * <p>Missing parent directories are created. After the temporary JAR is
   * successfully written and closed, an existing destination directory is
   * rejected; an existing file or symbolic link is warned about and deleted.
   * The temporary file is then moved without replacing an existing target.</p>
   *
   * <p>Despite the method name, publication does not request an atomic move.
   * If moving fails after destination deletion, the previous output is already
   * lost. Any remaining temporary file is deleted in a {@code finally} block.</p>
   *
   * @param output destination archive path.
   * @param manifest manifest written to the output archive.
   * @param entries archive entries to write after the manifest.
   * @throws IOException if directory creation, temporary archive writing or
   *                     closure, destination deletion, moving, or cleanup fails,
   *                     or if the destination is a directory.
   */
  private static void writeAtomically(Path output, Manifest manifest,
                                      List<Entry> entries)
    throws IOException
  {
    Path parent = output.getParent();
    if (parent != null)
      {
        Files.createDirectories(parent);
      }
    Path directory = parent == null ? Path.of(".") : parent;
    Path temporary = Files.createTempFile(directory,
                                          ".serviceloader-weaver-",
                                          ".jar");
    try
      {
        try (OutputStream stream = Files.newOutputStream(temporary);
            JarOutputStream jar = new JarOutputStream(stream, manifest))
          {
            writeEntries(jar, entries);
          }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS))
          {
            if (Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS))
              {
                throw new IOException("Output path is a directory: " + output);
              }
            System.err.println("Warning: deleting existing output file: " + output);
            Files.delete(output);
          }
        Files.move(temporary, output);
      }
    finally
      {
        Files.deleteIfExists(temporary);
      }
  }

  /**
   * Writes entry paths and content to an open archive output stream.
   *
   * <p>Directory entries have no content. Original timestamps and ZIP metadata
   * are not restored. Each written entry is closed, but the caller-owned
   * archive output stream remains open.</p>
   *
   * @param output destination archive stream.
   * @param entries entries to write in list order.
   * @throws IOException if creating, writing, or closing an entry fails.
   */
  private static void writeEntries(JarOutputStream output, List<Entry> entries)
    throws IOException
  {
    for (Entry source : entries)
      {
        JarEntry entry = new JarEntry(source.name_);
        output.putNextEntry(entry);
        if (!source.directory_)
          {
            output.write(source.bytes_);
          }
        output.closeEntry();
      }
  }

  /**
   * Reads the remaining bytes of a stream into memory without closing it.
   *
   * @param input caller-owned stream to read through end of stream.
   * @return bytes read from the current position through end of stream.
   * @throws IOException if reading fails.
   */
  private static byte[] readAll(InputStream input)
    throws IOException
  {
    try (ByteArrayOutputStream output = new ByteArrayOutputStream())
      {
        input.transferTo(output);
        return output.toByteArray();
      }
  }

}
