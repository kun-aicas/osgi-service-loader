/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.itests.basic;

import static org.junit.Assert.assertEquals;

import java.nio.file.Path;
import java.util.jar.JarFile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.osgi.framework.Bundle;

import com.aicas.osgi.spi.itests.support.AbstractTest;
import com.aicas.osgi.spi.weaver.Main;
import com.aicas.osgi.spi.weaver.StaticWeavingStatus;
import com.aicas.osgi.spi.weaver.WeaverConstants;

/** Verifies statically woven consumers while the runtime hook remains active. */
public class StaticWeaverTest extends AbstractTest
{
  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void staticallyWovenConsumerDiscoversProvider()
    throws Exception
  {
    Path source = findBundleJar(Path.of("../example", BUNDLE_CONSUMER),
                                BUNDLE_CONSUMER);
    Path outputDirectory =
      temporaryFolder.newFolder("serviceloader-static-consumer").toPath();
    Main.weave(source, outputDirectory);
    String name = source.getFileName().toString();
    int extension = name.lastIndexOf('.');
    String baseName = extension > 0 ? name.substring(0, extension) : name;
    Path woven = outputDirectory.resolve(baseName + "-spi-consumer.jar");
    try (JarFile jar = new JarFile(woven.toFile()))
      {
        assertEquals(StaticWeavingStatus.WOVEN.getValue(),
                     jar.getManifest().getMainAttributes().
                         getValue(WeaverConstants.STATIC_WEAVING_HEADER));
      }

    Bundle provider = install(BUNDLE_PROVIDER);
    Bundle consumer = install(BUNDLE_CONSUMER, woven);
    provider.start();
    int outputOffset = outputLength();
    consumer.start();

    awaitOutputContainsAfter(outputOffset,
                             "[consumer] Results from all SPIs:",
                             FRAGMENT_PROVIDER_MESSAGE);

    consumer.stop();
    provider.stop();
  }

  /** Verifies static weaving and service discovery for code in an embedded JAR. */
  @Test
  public void staticallyWovenEmbeddedConsumerDiscoversProvider()
    throws Exception
  {
    Path source = findBundleJar(Path.of("testbundles",
                                        BUNDLE_EMBEDDED_CONSUMER_BUNDLE),
                                BUNDLE_EMBEDDED_CONSUMER_BUNDLE);
    Path outputDirectory =
      temporaryFolder.newFolder("serviceloader-static-embedded-consumer").toPath();
    Main.weave(source, outputDirectory);
    String name = source.getFileName().toString();
    int extension = name.lastIndexOf('.');
    String baseName = extension > 0 ? name.substring(0, extension) : name;
    Path woven = outputDirectory.resolve(baseName + "-spi-consumer.jar");
    try (JarFile jar = new JarFile(woven.toFile()))
      {
        assertEquals(StaticWeavingStatus.WOVEN.getValue(),
                     jar.getManifest().getMainAttributes().
                         getValue(WeaverConstants.STATIC_WEAVING_HEADER));
      }

    Bundle provider = install(BUNDLE_PROVIDER_METADATA);
    Bundle consumer = install(BUNDLE_EMBEDDED_CONSUMER_BUNDLE, woven);
    provider.start();
    int outputOffset = outputLength();
    consumer.start();

    assertEquals(Bundle.ACTIVE, consumer.getState());
    awaitOutputContainsAfter(outputOffset,
                             "[embedded consumer] " + REGULAR_PROVIDER_MESSAGE);

    consumer.stop();
    provider.stop();
  }

  /** Verifies supported calls are woven while the unsupported stream path still runs. */
  @Test
  public void staticallyWovenConsumerV2PreservesUnsupportedStreamCall()
    throws Exception
  {
    Path source = findBundleJar(Path.of("testbundles", BUNDLE_CONSUMER_V2),
                                BUNDLE_CONSUMER_V2);
    Path outputDirectory =
      temporaryFolder.newFolder("serviceloader-static-consumer-v2").toPath();
    Main.weave(source, outputDirectory);
    String name = source.getFileName().toString();
    int extension = name.lastIndexOf('.');
    String baseName = extension > 0 ? name.substring(0, extension) : name;
    Path woven = outputDirectory.resolve(baseName + "-spi-consumer.jar");
    try (JarFile jar = new JarFile(woven.toFile()))
      {
        assertEquals(StaticWeavingStatus.WOVEN.getValue(),
                     jar.getManifest().getMainAttributes().
                         getValue(WeaverConstants.STATIC_WEAVING_HEADER));
      }

    Bundle provider = installTestBundle(BUNDLE_PROVIDER_V2);
    Bundle consumer = install(BUNDLE_CONSUMER_V2, woven);
    provider.start();
    int outputOffset = outputLength();
    consumer.start();

    assertEquals(Bundle.ACTIVE, consumer.getState());
    awaitOutputContainsAfter(outputOffset, V2_PROVIDER_MESSAGE);

    // unsupported class can be called without exception.
    Class<?> unsupportedApiConsumer = consumer.loadClass(UNSUPPORTED_API_CONSUMER);
    unsupportedApiConsumer.getMethod("loadFirstProvider").invoke(null);
    assertEquals(Boolean.TRUE, unsupportedApiConsumer.
      getMethod("moduleLayerLookupFindsNoOsgiProvider").invoke(null));
    assertEquals(Boolean.TRUE, unsupportedApiConsumer.
      getMethod("moduleLayerLookupWithStaticReferenceFindsNoOsgiProvider").invoke(null));
    assertEquals(Boolean.TRUE, unsupportedApiConsumer.
      getMethod("installedLookupFindsNoOsgiProvider").invoke(null));
    assertEquals(Boolean.TRUE, unsupportedApiConsumer.
      getMethod("installedLookupWithStaticReferenceFindsNoOsgiProvider").invoke(null));

    consumer.stop();
    provider.stop();
  }
}
