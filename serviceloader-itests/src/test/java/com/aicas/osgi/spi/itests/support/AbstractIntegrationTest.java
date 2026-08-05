/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.itests.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Manifest;

import org.apache.felix.framework.FrameworkFactory;
import org.junit.After;
import org.junit.Before;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.log.Logger;
import org.osgi.service.log.LoggerFactory;

import com.aicas.osgi.spi.example.spi.SPIProvider;

/** Shared embedded-Felix support for ServiceLoader integration scenarios. */
public abstract class AbstractIntegrationTest
{
  protected static final String REGULAR_PROVIDER_MESSAGE =
      "Hello, I was registered via OSGi metadata.";
  protected static final String MODULE_INFO_PROVIDER_MESSAGE =
      "Hi, I was declared via module-info.java.";
  protected static final String FRAGMENT_PROVIDER_MESSAGE =
      "Hello, I was declared by META-INF/services in the fragment host.";
  protected static final String UPDATED_PROVIDER_MESSAGE =
      "Hello, I was provided by the updated provider revision.";
  protected static final String UPDATED_MODULE_INFO_PROVIDER_MESSAGE =
      "Hi, I was declared by the updated module-info.java revision.";
  protected static final String PROVIDER_CONSUMER_MESSAGE =
      "Hello, I was provided and consumed by the same bundle via module-info.java.";

  private Framework framework_;
  private BundleContext context_;
  private Path storage_;
  private PrintStream originalOut_;
  private ByteArrayOutputStream output_;

  @Before
  public void startFramework() throws Exception
  {
    originalOut_ = System.out;
    output_ = new ByteArrayOutputStream();
    System.setOut(new PrintStream(output_, true, StandardCharsets.UTF_8));

    storage_ = Files.createTempDirectory("serviceloader-itests");
    framework_ = new FrameworkFactory().newFramework(Map.of(
        Constants.FRAMEWORK_STORAGE, storage_.toString(),
        Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT,
        Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA,
        "org.osgi.service.log;version=1.5,"
        + "org.osgi.util.tracker;version=1.5,"
        + "org.objectweb.asm;version=9.10,"
        + "org.objectweb.asm.commons;version=9.10,"
        + "org.objectweb.asm.util;version=9.10"));
    framework_.init();
    context_ = framework_.getBundleContext();
    framework_.start();

    LoggerFactory loggerFactory = mock(LoggerFactory.class);
    when(loggerFactory.getLogger(any(Class.class))).thenReturn(mock(Logger.class));
    context_.registerService(LoggerFactory.class, loggerFactory, null);

    install("weaver", com.aicas.osgi.spi.weaver.ServiceLoaderWeaver.class).start();
    install("mediator", com.aicas.osgi.spi.proxy.internal.MediatorActivator.class).start();
    install("spi", SPIProvider.class).start();
  }

  @After
  public void stopFramework() throws Exception
  {
    try
      {
        if (framework_ != null)
          {
            framework_.stop();
            framework_.waitForStop(5000);
          }
        if (storage_ != null)
          {
            deleteTree(storage_);
          }
      }
    finally
      {
        if (originalOut_ != null)
          {
            System.setOut(originalOut_);
          }
      }
  }

  protected Bundle install(String location, String bundleArtifact) throws Exception
  {
    return install(location, findBundleJar(Path.of("../example", bundleArtifact),
                                           bundleArtifact));
  }

  /** Installs a bundle from the integration-test bundle fixtures. */
  protected Bundle installTestBundle(String location, String bundleArtifact)
      throws Exception
  {
    return install(location, findBundleJar(Path.of("testbundles", bundleArtifact),
                                           bundleArtifact));
  }

  /** Updates an installed bundle from another example bundle revision. */
  protected void update(Bundle bundle, String bundleArtifact) throws Exception
  {
    Path update = findBundleJar(Path.of("testbundles", bundleArtifact),
                                bundleArtifact);
    try (InputStream input = Files.newInputStream(update))
      {
        bundle.update(input);
      }
  }

  /** Refreshes bundle wirings and waits until the framework completes it. */
  protected void refreshBundles(Bundle... bundles) throws Exception
  {
    FrameworkWiring wiring = context_.getBundle(0).adapt(FrameworkWiring.class);
    if (wiring == null)
      {
        throw new IllegalStateException("Framework wiring is unavailable");
      }
    CountDownLatch completed = new CountDownLatch(1);
    wiring.refreshBundles(List.of(bundles),
        (FrameworkEvent event) -> completed.countDown());
    if (!completed.await(5, TimeUnit.SECONDS))
      {
        throw new AssertionError("Timed out while refreshing bundle wirings");
      }
  }

  protected Bundle install(String location, Class<?> bundleClass) throws Exception
  {
    Path path = Path.of(bundleClass.getProtectionDomain().getCodeSource()
                            .getLocation().toURI());
    if (Files.isDirectory(path))
      {
        path = createBundleJar(path, location + ".jar");
      }
    return install(location, path);
  }

  protected ServiceReference<?> awaitService(String className, boolean present)
      throws Exception
  {
    long deadline = System.currentTimeMillis() + 5000;
    do
      {
        ServiceReference<?>[] references = context_.getAllServiceReferences(null, null);
        ServiceReference<?> match = null;
        if (references != null)
          {
            for (ServiceReference<?> reference : references)
              {
                Object classes = reference.getProperty(Constants.OBJECTCLASS);
                if (classes instanceof String[] &&
                    java.util.Arrays.asList((String[])classes).contains(className))
                  {
                    match = reference;
                    break;
                  }
              }
          }
        if ((match != null) == present)
          {
            return match;
          }
        Thread.sleep(25);
      }
    while (System.currentTimeMillis() < deadline);
    return null;
  }

  /** Waits until the service registry contains the expected number of services. */
  protected int awaitServiceCount(String className, int expectedCount)
      throws Exception
  {
    long deadline = System.currentTimeMillis() + 5000;
    int count;
    do
      {
        count = countServices(className);
        if (count == expectedCount)
          {
            return count;
          }
        Thread.sleep(25);
      }
    while (System.currentTimeMillis() < deadline);
    return count;
  }

  private int countServices(String className) throws Exception
  {
    int count = 0;
    ServiceReference<?>[] references =
        context_.getAllServiceReferences(null, null);
    if (references != null)
      {
        for (ServiceReference<?> reference : references)
          {
            Object classes = reference.getProperty(Constants.OBJECTCLASS);
            if (classes instanceof String[] &&
                java.util.Arrays.asList((String[])classes).contains(className))
              {
                count++;
              }
          }
      }
    return count;
  }

  protected void assertOutputContains(String... expectedLines)
  {
    String output = output_.toString(StandardCharsets.UTF_8);
    for (String expectedLine : expectedLines)
      {
        assertTrue("Expected output to contain: " + expectedLine +
                   "\nActual output:\n" + output,
                   output.contains(expectedLine));
      }
  }

  /** Verifies that each expected line occurs exactly once in captured output. */
  protected void assertOutputContainsExactlyOnce(String... expectedLines)
  {
    String output = output_.toString(StandardCharsets.UTF_8);
    for (String expectedLine : expectedLines)
      {
        assertEquals("Expected output to contain exactly once: " + expectedLine
                     + "\nActual output:\n" + output,
                     1, countOccurrences(output, expectedLine));
      }
  }

  /** Returns the current captured output length in characters. */
  protected int outputLength()
  {
    return output_.toString(StandardCharsets.UTF_8).length();
  }

  protected void assertOutputDoesNotContain(String unexpectedLine)
  {
    String output = output_.toString(StandardCharsets.UTF_8);
    assertTrue("Expected output not to contain: " + unexpectedLine +
               "\nActual output:\n" + output,
               !output.contains(unexpectedLine));
  }

  /** Waits until all expected output lines have been captured. */
  protected void awaitOutputContains(String... expectedLines) throws Exception
  {
    long deadline = System.currentTimeMillis() + 5000;
    do
      {
        String output = output_.toString(StandardCharsets.UTF_8);
        boolean found = true;
        for (String expectedLine : expectedLines)
          {
            if (!output.contains(expectedLine))
              {
                found = false;
                break;
              }
          }
        if (found)
          {
            return;
          }
        Thread.sleep(25);
      }
    while (System.currentTimeMillis() < deadline);
    assertOutputContains(expectedLines);
  }

  /** Waits until all expected lines are captured after the given offset. */
  protected void awaitOutputContainsAfter(int offset, String... expectedLines)
      throws Exception
  {
    long deadline = System.currentTimeMillis() + 5000;
    do
      {
        String output = output_.toString(StandardCharsets.UTF_8);
        String appendedOutput = output.substring(Math.min(offset, output.length()));
        boolean found = true;
        for (String expectedLine : expectedLines)
          {
            if (!appendedOutput.contains(expectedLine))
              {
                found = false;
                break;
              }
          }
        if (found)
          {
            return;
          }
        Thread.sleep(25);
      }
    while (System.currentTimeMillis() < deadline);

    String output = output_.toString(StandardCharsets.UTF_8);
    String appendedOutput = output.substring(Math.min(offset, output.length()));
    for (String expectedLine : expectedLines)
      {
        assertTrue("Expected appended output to contain: " + expectedLine
                   + "\nAppended output:\n" + appendedOutput,
                   appendedOutput.contains(expectedLine));
      }
  }

  private static int countOccurrences(String text, String expected)
  {
    int count = 0;
    int offset = 0;
    while ((offset = text.indexOf(expected, offset)) >= 0)
      {
        count++;
        offset += expected.length();
      }
    return count;
  }

  private Bundle install(String location, Path path) throws Exception
  {
    try (InputStream input = Files.newInputStream(path))
      {
        return context_.installBundle(location, input);
      }
  }

  private static Path findBundleJar(Path project, String artifact)
      throws IOException
  {
    Path target = project.resolve("target");
    try (java.util.stream.Stream<Path> paths = Files.list(target))
      {
        return paths.filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString()
                .startsWith(artifact + "-"))
            .filter(path -> path.getFileName().toString().endsWith(".jar"))
            .filter(path -> !path.getFileName().toString().endsWith("-sources.jar"))
            .findFirst()
            .orElseThrow(() -> new IOException("Bundle JAR not found for "
                                                + artifact + " in " + target));
      }
  }

  private Path createBundleJar(Path classes, String fileName) throws IOException
  {
    Path jar = storage_.resolve(fileName);
    Manifest manifest = new Manifest();
    try (InputStream input = Files.newInputStream(classes.resolve("META-INF/MANIFEST.MF")))
      {
        manifest.read(input);
      }
    try (java.util.jar.JarOutputStream output =
             new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest);
         java.util.stream.Stream<Path> paths = Files.walk(classes))
      {
        for (Path path : paths.filter(Files::isRegularFile).toList())
          {
            if (path.endsWith("META-INF/MANIFEST.MF"))
              {
                continue;
              }
            output.putNextEntry(new java.util.jar.JarEntry(
                classes.relativize(path).toString().replace('\\', '/')));
            Files.copy(path, output);
            output.closeEntry();
          }
      }
    return jar;
  }

  private static void deleteTree(Path root) throws IOException
  {
    try (java.util.stream.Stream<Path> paths = Files.walk(root))
      {
        paths.sorted(java.util.Comparator.reverseOrder())
            .forEach(path ->
            {
              try
                {
                  Files.deleteIfExists(path);
                }
              catch (IOException e)
                {
                  throw new RuntimeException(e);
                }
            });
      }
  }
}
