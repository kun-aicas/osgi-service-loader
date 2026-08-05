# OSGi Service Loader

This project provides an OSGi service-loader mediator and the bytecode weaver
used by it.

## Requirements

- Java 17 or newer
- Maven 3.6.3 or newer

## Build

Build the complete project, including the examples, with:

```sh
mvn clean package
```

Build only the mediator and its required project modules with:

```sh
mvn -pl serviceloader-mediator -am package
```

Run the tests with:

```sh
mvn test
```

Run the integration tests with:

```sh
mvn -pl serviceloader-itests -am verify
```

This builds and packages the required project modules first, then runs the
integration tests from the standalone `serviceloader-itests` module. The tests
start an embedded Felix framework automatically; no separate OSGi runtime is
required.

To run only the metadata-provider tests after the project modules have been
built, use:

```sh
mvn -pl serviceloader-itests -am -Dit.test=MetadataProviderIntegrationTest verify
```

The `-am` option also builds the example bundles required by the test.

The bundles are created in the corresponding `target` directories:

```text
serviceloader-weaver/target/serviceloader-weaver-<version>.jar
serviceloader-mediator/target/serviceloader-mediator-<version>.jar
```

## ASM dependencies

The `serviceloader-weaver` bundle uses these ASM artifacts:

- `org.ow2.asm:asm`
- `org.ow2.asm:asm-util`
- `org.ow2.asm:asm-commons`
- `org.ow2.asm:asm-tree`
- `org.ow2.asm:asm-analysis`

These dependencies are required at runtime and must be installed as OSGi
bundles in the framework. Use matching versions for all five bundles.

## Running the mediator

Run it by installing the following bundles into an OSGi framework:

1. `serviceloader-weaver`.
2. `serviceloader-mediator`.

For example, in an OSGi console, install the two project bundles using their
paths from the build output:

```text
install file:/path/to/serviceloader-weaver/target/serviceloader-weaver-<version>.jar
install file:/path/to/serviceloader-mediator/target/serviceloader-mediator-<version>.jar
start <mediator-bundle-id>
```

The mediator registers the `osgi.serviceloader.processor` and
`osgi.serviceloader.registrar` extender capabilities.

## Consumer metadata

The mediator analyzes a consumer host bundle and all of its attached fragments
as one unit. It recognizes the host as a Service Loader consumer when this
combined metadata declares an `osgi.extender` requirement for
`osgi.serviceloader.processor`, then reads the associated
`osgi.serviceloader` requirements to identify the requested service types.

As an extension beyond the OSGi Service Loader Mediator specification, this
mediator also recognizes a bundle whose `module-info.class` declares
`uses <service-type>`. This fallback is used only when neither the host bundle
nor an attached fragment declares the processor-extender requirement. Such a
module-info consumer is registered for weaving even though it has no OSGi
processor-extender requirement. This metadata is specific to this mediator and
is not portable OSGi Service Loader Mediator metadata.

## Provider metadata

The mediator analyzes a provider host bundle and all of its attached fragments
as one unit. It recognizes the host as a Service Loader provider when this
combined metadata declares an `osgi.serviceloader` provide capability. It reads
the capability's attributes to identify the provided service types and the
provider classes listed in `META-INF/services/`. These providers are added to
the mediator's provider registry. If the combined metadata also declares the
`osgi.serviceloader.registrar` extender capability, the mediator registers OSGi
services for the provided service types as well. OSGi provider metadata takes
precedence over module metadata.

As another non-standard extension, if neither the provider host nor an attached
fragment supplies usable `osgi.serviceloader` capability metadata, the mediator
reads the host's `module-info.class` and processes `provides ... with ...`
declarations instead. Module-info providers are added to the mediator's
provider registry only; they are never registered as OSGi services. This
fallback is specific to this mediator and is not portable OSGi Service Loader
Mediator metadata.

To exercise the mediator, install and start example provider or consumer
bundles after installing its SPI bundle and the required OSGi Service Loader
extender support.


## ServiceLoader API support

The bytecode weaver currently supports `ServiceLoader.load`,
`ServiceLoader.loadInstalled`, `iterator`, `reload`, `findFirst`, and
`toString`. `ServiceLoader.stream()` is not yet supported.

`ServiceLoader.loadInstalled` creates a fresh Java-only loader. Provider
caches are scoped to an individual `ServiceLoader` instance, so this new loader
has no cached OSGi provider instances and does not consult the mediator
registry. It returns only providers declared in
`META-INF/services/<service-type>` that are visible to the system class loader.
To reuse cached OSGi providers, retain and reuse the same `ServiceLoader`
instance.

A class is woven only when every `ServiceLoader` invocation in that class is
supported. If it contains an unsupported invocation, such as `stream()`, the
weaver leaves the entire class unchanged. Keep unsupported calls in a separate
class if the remaining calls should be mediated.
