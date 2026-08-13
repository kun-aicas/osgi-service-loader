/*------------------------------------------------------------------------*
 * Copyright 2026, aicas GmbH; all rights reserved.
 * This header, including copyright notice, may not be altered or removed.
 *------------------------------------------------------------------------*/

package com.aicas.osgi.spi.proxy.internal;

import org.osgi.framework.Version;

public interface MediatorConstants
{
  String SPECIFICATION_VERSION_STRING = "1.0.0"; // TODO  which version should be used??
  Version SPECIFICATION_VERSION = new Version(SPECIFICATION_VERSION_STRING);
  String VERSION_ATTRIBUTE = "version";

  String PROVIDE_CAPABILITY = "Provide-Capability";
  String REQUIRE_CAPABILITY = "Require-Capability";
  String EXTENDER_CAPABILITY_NAMESPACE = "osgi.extender";
  String FILTER_DIRECTIVE = "filter";

  // ServiceLoader capability and related directive
  String SERVICELOADER_CAPABILITY_NAMESPACE = "osgi.serviceloader";
  String REGISTER_DIRECTIVE = "register";

  // Service registration property
  String SERVICELOADER_MEDIATOR_PROPERTY = "serviceloader.mediator";

  // The names of the extenders involved
  String PROCESSOR_EXTENDER_NAME = "osgi.serviceloader.processor";
  String REGISTRAR_EXTENDER_NAME = "osgi.serviceloader.registrar";

  // Pre-baked requirements for consumer and provider
  String CONSUMER_REQUIREMENT =
    EXTENDER_CAPABILITY_NAMESPACE + "; " + FILTER_DIRECTIVE +
                                ":=\"(" + EXTENDER_CAPABILITY_NAMESPACE + "=" +
                                PROCESSOR_EXTENDER_NAME + ")\"";
  String PROVIDER_REQUIREMENT =
    EXTENDER_CAPABILITY_NAMESPACE + "; " + FILTER_DIRECTIVE +
                                ":=\"(" + EXTENDER_CAPABILITY_NAMESPACE + "=" +
                                REGISTRAR_EXTENDER_NAME + ")\"";

  String METAINF_SERVICES = "META-INF/services";
  String MODULE_INFO = "module-info.class";
}
