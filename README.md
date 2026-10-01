# OSGi Service Loader

`java.util.ServiceLoader` normally discovers providers through a class loader's
`META-INF/services` resources. In OSGi, a consumer bundle cannot use that
mechanism to discover provider implementations in other bundles. This project
bridges that gap with an OSGi Service Loader mediator: a weaving hook redirects
supported `ServiceLoader` calls to a proxy that discovers compatible providers
through OSGi bundle wiring.

The mediator supports the standard OSGi Service Loader Mediator metadata and
also provides metadata-free discovery for existing bundles that use
`META-INF/services` or `module-info.class` service declarations.

## Repository layout

| Path | Purpose |
| --- | --- |
| `serviceloader-weaver` | ASM-based bytecode transformer that transforms supported `java.util.ServiceLoader` calls to the `ServiceLoader` proxy. |
| `serviceloader-mediator` | OSGi bundle containing the activator, metadata trackers, provider registry, weaving-hook registration, and proxy `ServiceLoader`. |
| `serviceloader-itests` | Integration tests that start an embedded Felix framework. |
| `serviceloader-realworld-itests` |Bnd/Felix compatibility tests for third-party APIs and providers, including JAXB, JSON, Mail, Validation, REST, Persistence, and WebSocket. |
| `example` | Example SPI, consumer, provider, fragment, module-info and OSGi-service-registry bundles. |


## Requirements

- Java 17 or later
- Maven 3.6.3 or later
- An OSGi framework for runtime use

## Build and test

Run these commands from this directory.

Build the complete reactor, including examples:

```sh
mvn clean package
```

Build the mediator and the project modules it requires:

```sh
mvn -pl serviceloader-mediator -am package
```

Run the integration suite:

```sh
mvn -pl serviceloader-itests -am verify
```

Run the real-world compatibility suite:

```sh
mvn -pl serviceloader-realworld-itests -am verify
```

Both integration suites build their required modules and start Felix
automatically; no separately installed OSGi runtime is needed for testing.

## Install in an OSGi framework

Install matching versions of these ASM bundles before resolving the project
bundles:

- `org.ow2.asm:asm`
- `org.ow2.asm:asm-util`
- `org.ow2.asm:asm-commons`
- `org.ow2.asm:asm-tree`
- `org.ow2.asm:asm-analysis`

Then install `serviceloader-weaver` and `serviceloader-mediator`, and start the
mediator. For example, in an OSGi console:

```text
install file:/path/to/asm-<version>.jar
install file:/path/to/asm-util-<version>.jar
install file:/path/to/asm-commons-<version>.jar
install file:/path/to/asm-tree-<version>.jar
install file:/path/to/asm-analysis-<version>.jar
install file:/path/to/serviceloader-weaver/target/serviceloader-weaver-<version>.jar
install file:/path/to/serviceloader-mediator/target/serviceloader-mediator-<version>.jar
start <mediator-bundle-id>
```

The mediator exposes the `osgi.serviceloader.processor` and
`osgi.serviceloader.registrar` extender capabilities.

## Provider and consumer modes

The mediator evaluates a host bundle and its attached fragments together.

| Mode | Provider declaration | Consumer behavior |
| --- | --- | --- |
| Standard OSGi Service Loader Mediator | Publish an `osgi.serviceloader` capability and use `META-INF/services` for provider classes. The optional `osgi.serviceloader.registrar` extender also registers providers in the OSGi service registry. | Require the `osgi.serviceloader.processor` extender. Optional `osgi.serviceloader` requirements restrict visibility to the selected provider wires. |
| Metadata-free extension | Declare providers in `META-INF/services` and/or `module-info.class` `provides ... with ...` clauses. These entries are added to the mediator registry only; they are not registered as OSGi services. Providers that declare `osgi.serviceloader` metadata are included as well, so metadata-free consumers can discover them. | Bundles without an `osgi.serviceloader.processor` extender are woven automatically and discover compatible scanned providers only when both bundles are wired to the same service-type package. |

A standard metadata consumer does not discover metadata-free providers. A
metadata-free consumer can discover both metadata-free and standard-metadata
providers when the consumer and provider are wired to the same Service Type
package.

## Weaver

`ServiceLoaderWeaver` operates on one class file at a time. It validates all
`java.util.ServiceLoader` invocations and relevant method handles before
returning transformed bytes. If it finds an unsupported invocation or cannot
transform the class, it leaves the entire class unchanged, preventing JDK and
proxy ServiceLoader values from mixing in one class.

For a successful class, the weaver remaps supported `java.util.ServiceLoader`
calls to the proxy type. It supports:

- `ServiceLoader.load(Class)`
- `ServiceLoader.load(Class, ClassLoader)`
- `iterator()`,
- `reload()`,
- `findFirst()`,
- `toString()`.

`load(ModuleLayer, Class)`, `loadInstalled(Class)`, and `stream()` are
unsupported.

The static `load` overloads need the woven caller class, which
the original bytecode does not provide. The weaver therefore generates at most
two private static `serviceLoaderBridge$load` methods to preserve the
original argument shapes:

```text
java.util.ServiceLoader.load(service)
  -> Consumer.serviceLoaderBridge$load(service)
  -> com.aicas.osgi.spi.proxy.ServiceLoader.load(service, Consumer.class)

java.util.ServiceLoader.load(service, loader)
  -> Consumer.serviceLoaderBridge$load(service, loader)
  -> com.aicas.osgi.spi.proxy.ServiceLoader.load(service, loader, Consumer.class)
```

`Consumer` is the woven application class that originally invoked
`java.util.ServiceLoader`; `Consumer.class` lets the proxy `ServiceLoader`
identify that class's OSGi bundle.

## Proxy ServiceLoader

`com.aicas.osgi.spi.proxy.ServiceLoader` replaces the JDK type inside a woven
class. It combines visible mediator providers with a real
`java.util.ServiceLoader` delegate.

The mediator selects a provider only when the consumer and provider resolve the
Service Type package to the same `osgi.wiring.package` capability. It loads the
provider implementation through the provider bundle's class loader.

When mediation is enabled, `iterator()` returns providers in this order:

1. Visible mediator providers that already have cached instances.
2. Remaining visible mediator providers; successfully created instances are cached.
3. Providers from the Java ServiceLoader delegate.

This combined iterator suppresses duplicate provider classes across the mediator
and Java-delegate sequences, so each class is returned at most once per iterator.
`findFirst()` uses the same order.

If a mediator provider cannot start, load, or instantiate, the proxy logs a
warning and skips it so iteration can continue with the next mediator provider.
The failed entry is removed from the cache. Errors from the Java ServiceLoader
delegate retain normal JDK behavior.

Mediator provider instances are cached per proxy loader. Each new iterator reads
the current mediator registry and builds a fresh snapshot of the providers visible
to the consumer. Later iterators can therefore discover newly registered providers
and omit providers whose registrations have been removed. An existing iterator
retains its original snapshot. Cached instances whose definitions are absent from
a new snapshot are removed.

`reload()` clears both the mediator provider cache and the Java delegate cache.
As with `java.util.ServiceLoader`, obtain a new iterator after calling
`reload()`.


When the proxy loads a selected mediator provider, it starts the provider’s host
bundle on demand if it is not already active or starting. After the last dependent
consumer leaves, the mediator can stop an idle host after a grace period. Set the
framework property `com.aicas.osgi.spi.provider.stop.delay.millis` to a positive
delay in milliseconds; the default is 50 seconds.

For `load(Class)`, mediation uses the woven caller's OSGi bundle when it has
one. For `load(Class, ClassLoader)`, mediation applies only when the requested
loader is that caller's bundle class loader; any other loader uses ordinary
Java ServiceLoader discovery.

When the mediator is unavailable or mediation is disabled for the loader, the
proxy clears its mediator-provider cache and returns only Java-delegate
providers.

## Examples

Install `serviceloader-spi` before running any of these scenarios:

- **Metadata-free discovery:** Pair `serviceloader-consumer` with either
  `serviceloader-provider-moduleinfo` or `serviceloader-provider`.
- **Standard mediator metadata discovery:** Pair `serviceloader-consumer-metadata` with
  `serviceloader-provider-metadata`. Add `serviceloader-osgi-client` to see
  the registrar-created OSGi service.
- **Fragment metadata:** Install `serviceloader-provider-fragment` and its
  `serviceloader-provider` host, then run it with
  `serviceloader-consumer-metadata`.
- **Mixed discovery:** Pair `serviceloader-consumer` with
  `serviceloader-provider-metadata` to show that a metadata-free consumer
  discovers a provider that declares standard `osgi.serviceloader` metadata.
